package dev.lexicon.domain

import java.net.URI
import java.security.MessageDigest

enum class ReadingDisclosureType { CONFLICT_OF_INTEREST, FUNDING, AFFILIATION }

/** A presentation hint into the unchanged original; it does not remove or replace source text. */
data class ReadingDisclosure(
    val paragraphIndex: Int,
    val start: Int,
    val endExclusive: Int,
    val type: ReadingDisclosureType,
)

/** Recognises a deliberately small set of complete, trailing author declarations. Unknown text stays ordinary reading. */
object ReadingDisclosures {
    private const val MAX_TRAILING_PARAGRAPHS = 4
    private const val MAX_DECLARATION_LENGTH = 480
    private const val MAX_ORGANISATION_LENGTH = 180
    private val spaces = Regex("\\s+")
    private val words = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)*")
    private val organisationConnectors = setOf("a", "an", "the", "and", "of", "for", "in", "on", "at", "to", "with", "by", "as", "or")
    private val genericSingular = Regex(
        "The author does not work for, consult, own shares in or receive funding from any company or " +
            "organi[sz]ation that would benefit from this article, and has disclosed no relevant affiliations beyond their academic appointment\\.",
    )
    private val genericPlural = Regex(
        "The authors do not work for, consult, own shares in or receive funding from any company or " +
            "organi[sz]ation that would benefit from this article, and have disclosed no relevant affiliations beyond their academic appointment\\.",
    )
    private val nameToken = Regex("[\\p{L}][\\p{L}’'\\-]*(?:\\.)?")
    private val entityCharacters = Regex("[\\p{L}\\p{N} ,&()/'’\\-]+")
    private val standaloneApostrophe = Regex("(?<!\\p{L})['’]|['’](?!\\p{L})")

    fun detect(article: Article, body: ArticleBody): List<ReadingDisclosure> {
        if (article.readingMode != ReadingMode.NATIVE || article.id.isBlank() || article.id != body.articleId ||
            body.contentHash != digest(body.text) || !isSupportedSource(article.url)) return emptyList()
        val paragraphs = ParagraphFocus.paragraphs(body.text)
        if (paragraphs.size < 3) return emptyList()
        val articleAuthor = authorName(article.author)
        val bodyAuthor = authorName(body.author)
        if (article.author.isNotBlank() && body.author.isNotBlank() && articleAuthor != bodyAuthor) return emptyList()
        val author = bodyAuthor ?: articleAuthor
        val result = mutableListOf<ReadingDisclosure>()
        // Keep at least the first two paragraphs as ordinary reading, even for pathological all-template input.
        for (index in paragraphs.lastIndex downTo maxOf(2, paragraphs.size - MAX_TRAILING_PARAGRAPHS)) {
            val paragraph = paragraphs[index]
            val type = classify(body.text.substring(paragraph.start, paragraph.endExclusive), author) ?: break
            result += ReadingDisclosure(index, paragraph.start, paragraph.endExclusive, type)
        }
        return result.asReversed()
    }

    private fun isSupportedSource(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme?.lowercase() in setOf("http", "https") && uri.rawUserInfo == null &&
            uri.host?.lowercase() in setOf("theconversation.com", "www.theconversation.com") &&
            uri.port in setOf(-1, 80, 443)
    }.getOrDefault(false)

    private fun authorName(metadata: String): String? {
        val name = metadata.substringBefore(',').substringBefore(" and ").substringBefore(" & ").trim()
        if (name.length !in 3..100 || name.any { it.isISOControl() }) return null
        val tokens = name.split(' ')
        if (tokens.size !in 2..6 || tokens.any { !nameToken.matches(it) }) return null
        return name
    }

    private fun classify(raw: String, author: String?): ReadingDisclosureType? {
        if (raw.length > MAX_DECLARATION_LENGTH || raw.any { it == '\u0000' || it == '\u2028' || it == '\u2029' }) return null
        val text = spaces.replace(raw.trim(), " ")
        if (genericSingular.matches(text) || genericPlural.matches(text)) return ReadingDisclosureType.CONFLICT_OF_INTEREST
        if (author == null || !text.startsWith("$author ")) return null
        val remainder = text.removePrefix("$author ")
        val sentences = remainder.split(". ")
        if (sentences.size !in 1..3 || !sentences.last().endsWith('.')) return null
        val declarations = sentences.mapIndexed { index, sentence ->
            val value = if (index == sentences.lastIndex) sentence.dropLast(1) else sentence
            declaration(value, index == 0) ?: return null
        }
        // A declaration may report both funding and affiliation; funding is the more specific label.
        return if (ReadingDisclosureType.FUNDING in declarations) ReadingDisclosureType.FUNDING else declarations.first()
    }

    private fun declaration(sentence: String, first: Boolean): ReadingDisclosureType? {
        val funding = if (first) listOf("receives funding from ") else
            listOf("He receives funding from ", "She receives funding from ", "They receive funding from ")
        val affiliation = if (first) listOf("is affiliated with ") else
            listOf("He is affiliated with ", "She is affiliated with ", "They are affiliated with ")
        funding.firstOrNull(sentence::startsWith)?.let { prefix ->
            val entity = sentence.removePrefix(prefix)
            val suffix = listOf(" for her scholarly research", " for his scholarly research", " for their scholarly research")
                .firstOrNull(entity::endsWith)
            return ReadingDisclosureType.FUNDING.takeIf { isOrganisation(if (suffix == null) entity else entity.removeSuffix(suffix)) }
        }
        affiliation.firstOrNull(sentence::startsWith)?.let { prefix ->
            return ReadingDisclosureType.AFFILIATION.takeIf { isOrganisation(sentence.removePrefix(prefix)) }
        }
        return null
    }

    private fun isOrganisation(value: String): Boolean {
        if (value.length !in 2..MAX_ORGANISATION_LENGTH || !entityCharacters.matches(value) ||
            standaloneApostrophe.containsMatchIn(value)) return false
        val tokens = words.findAll(value).map { it.value }.toList()
        if (tokens.size !in 1..28 || tokens.any { token ->
                token !in organisationConnectors && !token.first().isUpperCase() && !token.first().isDigit()
            }) return false
        val beginning = value.removePrefix("the ").firstOrNull() ?: return false
        if (!beginning.isUpperCase() && !beginning.isDigit()) return false
        var parentheses = 0
        value.forEach { character ->
            if (character == '(') parentheses++
            if (character == ')') parentheses--
            if (parentheses !in 0..1) return false
        }
        return parentheses == 0 && value.last().let { it.isLetterOrDigit() || it == ')' }
    }

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
