package dev.lexicon.domain

import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.min
import kotlin.math.pow

/**
 * Reviewable English chunking heuristic. It protects known word groups, then scores legal
 * boundaries with dynamic programming. It does not claim to be a parser or a unique gold split.
 */
object SpeedChunker {
    const val MAX_UNIT_PARAGRAPHS = 3
    const val MAX_UNIT_CHARACTERS = 9_000
    private data class Word(val start: Int, val end: Int, val text: String)
    private data class Sentence(val start: Int, val end: Int)

    fun wordCount(text: String): Int = WORD.findAll(text).count()

    fun plan(
        articleId: String, bodyHash: String, text: String, granularity: SpeedGranularity,
        startParagraph: Int, paragraphCount: Int, now: Long = System.currentTimeMillis(),
    ): SpeedPlan {
        val paragraphs = ParagraphFocus.paragraphs(text)
        require(startParagraph in paragraphs.indices) { "请选择正文中的起始段落" }
        require(paragraphCount in 1..MAX_UNIT_PARAGRAPHS) { "每次训练选择 1–3 段" }
        val selected = (startParagraph until min(startParagraph + paragraphCount, paragraphs.size)).toList()
        require(selected.sumOf { paragraphs[it].endExclusive - paragraphs[it].start } <= MAX_UNIT_CHARACTERS) {
            "本次段落超过 9,000 字符，请减少段数或选择较短段落"
        }
        var sentenceIndex = 0
        val chunks = selected.flatMap { paragraphIndex ->
            val paragraph = paragraphs[paragraphIndex]
            sentences(text, paragraph.start, paragraph.endExclusive).flatMap { sentence ->
                split(text, sentence, granularity, paragraphIndex, sentenceIndex++)
            }
        }
        val result = SpeedPlan(articleId, bodyHash, granularity, selected, chunks, createdAt = now)
        validate(result, text)
        return result
    }

    /** Rejects missing, duplicated, moved or ungrounded ranges before displaying either AI or rules. */
    fun validate(plan: SpeedPlan, text: String) {
        require(plan.version == 1 && plan.articleId.isNotBlank() && plan.bodyHash.isNotBlank()) { "意群计划标识无效" }
        require(plan.paragraphIndices.size in 1..MAX_UNIT_PARAGRAPHS &&
            plan.paragraphIndices == plan.paragraphIndices.distinct().sorted()) { "训练段落无效" }
        val paragraphs = ParagraphFocus.paragraphs(text)
        require(plan.paragraphIndices.all { it in paragraphs.indices }) { "训练段落超出正文" }
        require(plan.paragraphIndices.zipWithNext().all { (left, right) -> right == left + 1 }) { "训练段落必须连续" }
        require(plan.paragraphIndices.sumOf { paragraphs[it].endExclusive - paragraphs[it].start } <= MAX_UNIT_CHARACTERS)
        require(plan.chunks.isNotEmpty() && plan.chunks.size <= MAX_UNIT_CHARACTERS) { "意群不能为空" }
        require(plan.chunks == plan.chunks.sortedBy { it.start }) { "意群顺序与原文不一致" }
        require(plan.chunks.all { it.paragraph in plan.paragraphIndices }) { "意群不属于训练段落" }
        val expectedSentences = plan.paragraphIndices.flatMap { index ->
            val p = paragraphs[index]
            sentences(text, p.start, p.endExclusive).map { index to it }
        }
        expectedSentences.forEachIndexed { sentenceIndex, (paragraphIndex, sentence) ->
            val chunks = plan.chunks.filter { it.paragraph == paragraphIndex && it.sentence == sentenceIndex }
            require(chunks.isNotEmpty()) { "原句存在遗漏" }
            val words = words(text, sentence.start, sentence.end)
            require(words.isNotEmpty()) { "段落需要包含可训练的英文单词" }
            val protected = forbiddenBoundaries(text, words)
            var cursor = sentence.start
            for (chunk in chunks) {
                require(chunk.start == cursor && chunk.end > chunk.start && chunk.end <= sentence.end &&
                    !splitsSurrogate(text, chunk.start) && !splitsSurrogate(text, chunk.end)) { "意群必须完整覆盖原句且不能拆分字符" }
                require(chunk.reason.isNotBlank() && chunk.reason.length <= 120) { "意群缺少简短切分理由" }
                require(chunk.wordCount == wordCount(text.substring(chunk.start, chunk.end)) && chunk.wordCount > 0) { "意群词数不符" }
                if (chunk.end != sentence.end) {
                    val boundary = words.indexOfFirst { it.start == chunk.end }
                    require(boundary > 0 && !protected[boundary]) { "意群断开了单词或紧密搭配" }
                }
                cursor = chunk.end
            }
            require(cursor == sentence.end) { "原句末尾存在遗漏" }
        }
        require(plan.chunks.all { it.sentence in expectedSentences.indices && expectedSentences[it.sentence].first == it.paragraph }) { "句子编号与原文不符" }
        require(plan.questions.isEmpty() || plan.questions.map { it.paragraph }.sorted() == plan.paragraphIndices) { "每段必须对应一道理解题" }
        require(plan.questions.map { it.id }.distinct().size == plan.questions.size)
        plan.questions.forEach { question ->
            val p = paragraphs[question.paragraph]
            require(question.id.isNotBlank() && question.id.length <= 160 && question.prompt.isNotBlank() && question.prompt.length <= 600)
            require(question.options.size in 2..4 && question.options.all { it.isNotBlank() && it.length <= 400 } &&
                question.options.distinct().size == question.options.size && question.answerIndex in question.options.indices)
            require(question.evidenceStart >= p.start && question.evidenceEnd <= p.endExclusive && question.evidenceEnd > question.evidenceStart &&
                !splitsSurrogate(text, question.evidenceStart) && !splitsSurrogate(text, question.evidenceEnd)) { "理解题证据不属于原段落" }
            require(wordCount(text.substring(question.evidenceStart, question.evidenceEnd)) > 0) { "理解题证据不能为空白" }
            require(question.explanation.isNotBlank() && question.explanation.length <= 1200)
        }
    }

    /** Source-derived plan identity; timestamps and storage aliases do not change an exercise. */
    fun planKey(plan: SpeedPlan): String = MessageDigest.getInstance("SHA-256")
        .digest(Json.encodeToString(plan.copy(createdAt = 0, planKey = "")).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** All hard-legal internal word boundaries, rather than just boundaries chosen by one plan. */
    fun legalBreaks(text: String, start: Int, end: Int): List<Int> {
        require(start >= 0 && end > start && end <= text.length && !splitsSurrogate(text, start) && !splitsSurrogate(text, end))
        require(sentences(text, start, end).size == 1) { "每次调整一个完整句子" }
        val words = words(text, start, end)
        require(words.isNotEmpty())
        val forbidden = forbiddenBoundaries(text, words)
        return words.indices.drop(1).filterNot { forbidden[it] }.map { words[it].start }
    }

    private fun split(text: String, sentence: Sentence, level: SpeedGranularity, paragraph: Int, sentenceIndex: Int): List<SenseChunk> {
        val words = words(text, sentence.start, sentence.end)
        require(words.isNotEmpty()) { "该段没有可训练的英文单词，请选择其他段落" }
        val forbidden = forbiddenBoundaries(text, words)
        val softCosts = prepositionBoundaryCosts(text, words)
        val size = words.size
        val cost = DoubleArray(size + 1) { Double.POSITIVE_INFINITY }
        val previous = IntArray(size + 1) { -1 }
        val strengths = DoubleArray(size) { index -> if (index > 0 && !forbidden[index]) boundaryStrength(text, words, index) else 0.0 }
        val prefixStrength = DoubleArray(size + 1)
        for (index in 0 until size) prefixStrength[index + 1] = prefixStrength[index] + strengths[index]
        cost[0] = 0.0
        for (end in 1..size) {
            if (end < size && forbidden[end]) continue
            for (start in end - 1 downTo 0) {
                if (start > 0 && forbidden[start] || !cost[start].isFinite()) continue
                val length = end - start
                var candidateCost = cost[start] + 2.0 + (length - level.preferredWords).toDouble().pow(2.0) * .55
                if (length < level.minimumWords) candidateCost += (level.minimumWords - length) * 3.0
                if (length > level.maximumWords) candidateCost += (length - level.maximumWords).toDouble().pow(2.0) * 2.5
                // Clause/punctuation boundaries should survive when chunk length can remain natural.
                if (end < size) candidateCost -= strengths[end]
                if (end < size) candidateCost += softCosts[end]
                candidateCost += (prefixStrength[end] - prefixStrength[start + 1]) * .3
                if (candidateCost < cost[end] - .00001) { cost[end] = candidateCost; previous[end] = start }
            }
        }
        require(previous[size] >= 0)
        val ranges = mutableListOf<Pair<Int, Int>>()
        var end = size
        while (end > 0) { val start = previous[end]; ranges += start to end; end = start }
        return ranges.asReversed().map { (from, to) ->
            val start = if (from == 0) sentence.start else words[from].start
            val finish = if (to == size) sentence.end else words[to].start
            SenseChunk(start, finish, paragraph, sentenceIndex, to - from, reason(text, words, from, to))
        }
    }

    private fun forbiddenBoundaries(text: String, words: List<Word>): BooleanArray {
        val forbidden = BooleanArray(words.size + 1)
        fun protect(from: Int, through: Int) { for (index in from + 1..through.coerceAtMost(words.lastIndex)) forbidden[index] = true }
        val forms = words.map { canonical(it.text) }
        for (index in words.indices) {
            FIXED.forEach { phrase ->
                if (index + phrase.size <= words.size && phrase.indices.all { forms[index + it] == phrase[it] } &&
                    (index until index + phrase.lastIndex).all { !hardGap(text, words[it], words[it + 1]) }) protect(index, index + phrase.lastIndex)
            }
            val current = forms[index]
            if (current in ABBREVIATIONS && index < words.lastIndex) protect(index, index + 1)
            if (current in DETERMINERS && index < words.lastIndex && !hardGap(text, words[index], words[index + 1])) {
                protect(index, nominalEnd(text, words, forms, index + 1))
            }
            // Closed adjective entries also cover bare noun phrases ("quiet reading rooms").
            // Do not treat every -ed/-ing verb as an adjective outside a nominal context.
            if (current in ADJECTIVES) protect(index, nominalEnd(text, words, forms, index))
            if (index < words.lastIndex && (current.endsWith("ly") || current in DEGREE_ADVERBS) &&
                isAdjective(forms[index + 1]) && !hardGap(text, words[index], words[index + 1]))
                protect(index, nominalEnd(text, words, forms, index))
            if (isQuantity(current)) protect(index, quantityEnd(text, words, forms, index))
            if (current in PREPOSITIONS && index < words.lastIndex) {
                if (current in TEMPORAL_PREPOSITIONS) {
                    val first = index + 1
                    // "before they decide" is a finite clause; "before deciding" has a gerund
                    // object. Stop a nominal time phrase before the next preposition/clause.
                    if (!hardGap(text, words[index], words[first]) && forms[first] !in SUBJECT_PRONOUNS &&
                        forms[first] !in NOMINAL_STOPS && forms[first] !in FINITE_VERBS) {
                        val end = when {
                            isQuantity(forms[first]) -> quantityEnd(text, words, forms, first)
                            forms[first] in DETERMINERS && first < words.lastIndex &&
                                !hardGap(text, words[first], words[first + 1]) -> nominalEnd(text, words, forms, first + 1)
                            else -> nominalEnd(text, words, forms, first)
                        }
                        protect(index, end)
                    }
                    continue
                }
                val next = index + 1
                // The first governed word is tightly linked. The old seven-word tail was only
                // a heuristic and must not lock an unknown following subject/predicate together.
                if (!hardGap(text, words[index], words[next]) && forms[next] !in CLAUSES && forms[next] !in CONJUNCTIONS &&
                    forms[next] !in FINITE_VERBS && forms[next] !in SUBJECT_PRONOUNS && forms[next] !in PREPOSITIONS)
                    protect(index, next)
            }
        }
        return forbidden
    }

    /** Prefer an intact preposition phrase, but allow human/AI alternatives past its first word. */
    private fun prepositionBoundaryCosts(text: String, words: List<Word>): DoubleArray {
        val costs = DoubleArray(words.size + 1)
        val forms = words.map { canonical(it.text) }
        for (index in words.indices) {
            if (forms[index] !in PREPOSITIONS) continue
            var last = index
            while (last < words.lastIndex && last - index < 7) {
                val next = last + 1
                if (hardGap(text, words[last], words[next]) || forms[next] in CLAUSES || forms[next] in CONJUNCTIONS ||
                    forms[next] in FINITE_VERBS || forms[next] in SUBJECT_PRONOUNS || next > index + 1 && forms[next] in PREPOSITIONS) break
                costs[next] = maxOf(costs[next], 3.0)
                last = next
            }
        }
        return costs
    }

    /** A bounded modifier chain and its first nominal head; punctuation remains a legal stop. */
    private fun nominalEnd(text: String, words: List<Word>, forms: List<String>, start: Int): Int {
        var last = start
        while (last < words.lastIndex && last - start < 7 &&
            (isAdjective(forms[last]) || (forms[last].endsWith("ly") || forms[last] in DEGREE_ADVERBS) && isAdjective(forms[last + 1])) &&
            !hardGap(text, words[last], words[last + 1]) && forms[last + 1] !in NOMINAL_STOPS) last++
        return last
    }

    /** Keep spelled/digit quantities, optional scale words, modifiers and the counted unit together. */
    private fun quantityEnd(text: String, words: List<Word>, forms: List<String>, start: Int): Int {
        var last = start
        while (last < words.lastIndex && last - start < 7 && !hardGap(text, words[last], words[last + 1])) {
            val next = last + 1
            if (isQuantity(forms[next])) last = next
            else if (forms[next] == "and" && next < words.lastIndex && isQuantity(forms[next + 1]) &&
                !hardGap(text, words[next], words[next + 1])) last = next + 1
            else break
        }
        if (last < words.lastIndex && !hardGap(text, words[last], words[last + 1]) &&
            forms[last + 1] !in NOMINAL_STOPS && forms[last + 1] !in FINITE_VERBS && forms[last + 1] !in SUBJECT_PRONOUNS) {
            last = nominalEnd(text, words, forms, last + 1)
        }
        return last
    }

    private fun boundaryStrength(text: String, words: List<Word>, index: Int): Double {
        val gap = text.substring(words[index - 1].end, words[index].start)
        val next = canonical(words[index].text)
        return when {
            gap.any { it in ";:—–" } -> 7.0
            gap.contains(',') -> 5.0
            next in CLAUSES -> 4.2
            next in CONJUNCTIONS -> 2.8
            next in PREPOSITIONS -> 2.4
            next in DETERMINERS -> 1.0
            else -> .0
        }
    }

    private fun reason(text: String, words: List<Word>, start: Int, end: Int): String {
        val first = canonical(words[start].text)
        return when {
            first in CLAUSES -> "从句起点 · 规则建议"
            first in PREPOSITIONS -> "介词短语 · 规则建议"
            first in CONJUNCTIONS -> "并列结构 · 规则建议"
            end == words.size -> "句末语义块 · 规则建议"
            text.substring(words[end - 1].end, words[end].start).contains(',') -> "标点停顿 · 规则建议"
            else -> "连续词组 · 规则建议"
        }
    }

    /** Sentence scan keeps abbreviations/decimals/acronyms intact; semicolons are clause stops. */
    private fun sentences(text: String, start: Int, end: Int): List<Sentence> {
        val result = mutableListOf<Sentence>()
        var from = start
        var i = start
        while (i < end) {
            val char = text[i]
            if (char in ".?!") {
                val decimal = char == '.' && i > start && i + 1 < end && text[i - 1].isDigit() && text[i + 1].isDigit()
                val internalDot = char == '.' && i + 1 < end && text[i + 1].isLetter()
                val before = text.substring(from, i + 1)
                val token = before.takeLastWhile { !it.isWhitespace() }.trimStart('"', '\'', '“', '‘', '(')
                val abbreviation = char == '.' && (token.trimEnd('.').lowercase(Locale.ROOT) in ABBREVIATIONS ||
                    Regex("(?:[A-Za-z]\\.){2,}").matches(token) || Regex("[A-Z]\\.").matches(token))
                if (!decimal && !internalDot && !abbreviation) {
                    var finish = i + 1
                    while (finish < end && text[finish] in ".?!\"'”’)]}") finish++
                    // Only split before whitespace/end; adjacent punctuation is kept with its word.
                    if (finish == end || text[finish].isWhitespace()) {
                        while (finish < end && text[finish].isWhitespace()) finish++
                        if (wordCount(text.substring(from, finish)) > 0) { result += Sentence(from, finish); from = finish }
                        i = finish - 1
                    }
                }
            }
            i++
        }
        if (from < end) {
            if (wordCount(text.substring(from, end)) > 0 || result.isEmpty()) result += Sentence(from, end)
            else result[result.lastIndex] = result.last().copy(end = end)
        }
        return result
    }

    private fun words(text: String, start: Int, end: Int): List<Word> = WORD.findAll(text.substring(start, end)).map {
        Word(start + it.range.first, start + it.range.last + 1, it.value)
    }.toList()
    private fun hardGap(text: String, left: Word, right: Word) = text.substring(left.end, right.start).any { it in ",;:—–!?" }
    private fun canonical(text: String) = text.lowercase(Locale.ROOT).trimEnd('.').let { value ->
        when (value) { "takes", "taking", "taken", "took" -> "take"; "makes", "making", "made" -> "make"; "plays", "playing", "played" -> "play"; else -> value }
    }
    private fun isAdjective(word: String) = word in ADJECTIVES || ADJECTIVE_SUFFIX.containsMatchIn(word)
    private fun isQuantity(word: String) = NUMBER.matches(word) || word.split('-').all { it in NUMBER_WORDS }
    private fun splitsSurrogate(text: String, at: Int) = at > 0 && at < text.length && text[at - 1].isHighSurrogate() && text[at].isLowSurrogate()
    private val WORD = Regex("(?:[A-Za-z]\\.){2,}|[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]+)?|[\\p{L}\\p{N}]+(?:[.’'’-][\\p{L}\\p{N}]+)*")
    private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "prof", "st", "sr", "jr", "vs", "etc", "e.g", "i.e", "fig", "no", "inc", "ltd")
    private val DETERMINERS = setOf("a", "an", "the", "this", "that", "these", "those", "my", "your", "his", "her", "its", "our", "their", "each", "every", "some", "any", "many", "much", "several", "both", "no")
    private val TEMPORAL_PREPOSITIONS = setOf("after", "before")
    private val PREPOSITIONS = setOf("in", "on", "at", "by", "for", "from", "with", "without", "of", "to", "into", "onto", "over", "under", "between", "among", "through", "across", "against", "during", "within", "beyond", "toward", "towards", "despite", "via") + TEMPORAL_PREPOSITIONS
    private val CLAUSES = setOf("which", "who", "whom", "whose", "that", "because", "although", "though", "when", "whenever", "where", "whereas", "while", "if", "unless", "until", "whether", "since")
    private val CONJUNCTIONS = setOf("and", "but", "or", "yet", "so", "nor")
    private val FINITE_VERBS = setOf("is", "are", "was", "were", "be", "been", "being", "has", "have", "had", "do", "does", "did", "can", "could", "will", "would", "should", "shall", "may", "might", "must", "warn", "warns", "say", "says", "said", "show", "shows", "showed", "face", "faces", "find", "finds", "found", "argue", "argues", "suggest", "suggests", "remain", "remains", "provide", "provides", "need", "needs", "mean", "means", "make", "take", "play", "help", "helps", "report", "reports", "conclude", "concludes")
    private val NOMINAL_STOPS = PREPOSITIONS + CLAUSES + CONJUNCTIONS + setOf("is", "are", "was", "were", "has", "have", "had", "will", "would", "may", "might", "must", "can", "could", "should")
    private val DEGREE_ADVERBS = setOf("very", "quite", "rather", "so", "too", "more", "most", "less", "least")
    private val SUBJECT_PRONOUNS = setOf("i", "you", "he", "she", "it", "we", "they", "there")
    private val NUMBER = Regex("[0-9]+(?:[.,][0-9]+)*")
    private val NUMBER_WORDS = setOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
        "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand", "million", "billion", "trillion")
    private val ADJECTIVES = setOf("new", "old", "long", "short", "large", "small", "big", "high", "low", "good", "bad", "main", "major", "minor", "clear", "strong", "weak", "recent", "current", "next", "last", "first", "second", "third", "public", "private", "local", "global", "national", "rising", "growing", "important", "robust", "different", "same", "other", "economic", "social", "political", "coastal", "climate",
        "quiet", "loud", "calm", "warm", "cool", "dark", "bright", "deep", "shallow", "wide", "narrow", "early", "late",
        "young", "older", "senior", "basic", "complex", "simple", "difficult", "easy", "relevant", "reliable", "valid",
        "complete", "incomplete", "fluent", "gradual", "smooth", "rapid", "slow", "quick", "careful", "efficient", "effective",
        "accurate", "precise", "stable", "safe", "secure", "independent", "dependent", "modern", "rural", "urban", "foreign", "domestic", "cultural",
        "financial", "legal", "scientific", "academic", "environmental", "digital", "physical", "mental", "human", "general", "regional",
        "specific", "common", "rare", "available", "necessary", "possible", "existing", "future", "previous", "detailed", "sustainable")
    private val ADJECTIVE_SUFFIX = Regex("(?:ive|ous|ful|less|able|ible|ical|ary|ing|ed)$")
    private val FIXED = listOf(
        "take into account", "take account of", "in terms of", "as a result of", "as a result", "in order to", "according to",
        "due to", "because of", "rather than", "such as", "as well as", "in addition to", "on behalf of", "in spite of",
        "with regard to", "with respect to", "in relation to", "in contrast to", "in response to", "in favour of", "in favor of",
        "as opposed to", "a number of", "the number of", "a wide range of", "play a role", "make use of", "point of view",
        "at the same time", "on the other hand", "not only", "but also", "no longer", "at least", "at most", "more than", "less than",
        "climate change", "artificial intelligence", "public health", "human rights", "supply chain", "interest rate", "interest rates",
        "sea level", "sea levels", "city district", "city districts",
    ).map { it.split(' ') }
}
