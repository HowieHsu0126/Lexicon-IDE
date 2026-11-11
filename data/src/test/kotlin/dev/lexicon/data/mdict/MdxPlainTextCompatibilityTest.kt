package dev.lexicon.data.mdict

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Freeze 1.20's complete visible-text result before optimizing HTML conversion.
 * Default inputs are synthetic; the complete personal corpus requires explicit opt-in.
 * The reference below deliberately uses no production helpers.
 */
class MdxPlainTextCompatibilityTest {
    @Test fun basicVisibleTextAndLineBoundariesHaveLiteralExpectedResults() {
        val fixtures = listOf(
            Fixture("", ""),
            Fixture("plain text", "plain text"),
            Fixture("  alpha\t  beta  ", "alpha beta"),
            Fixture("a&nbsp;&nbsp;b &amp; c &#x2014; d", "a b & c — d"),
            Fixture("a\r\nb\rc\n\n\t d", "a\nb\nc\nd"),
            Fixture("<span>one</span><span>two</span>", "onetwo"),
            Fixture("<p>alpha</p><div>beta</div>", "alpha\nbeta"),
            Fixture("<div>outer<section>inner<br>next</section>tail</div>", "outer\ninner\nnext\ntail"),
            Fixture("<br><br><hr><div></div><p>last</p><br>", "last"),
            Fixture("<ul><li>one</li><li>two</li></ul>", "one\ntwo"),
            Fixture("<blockquote>quote</blockquote><h1>title</h1><h4>end</h4>", "quote\ntitle\nend"),
            Fixture("alpha<!-- invisible -->beta", "alphabeta"),
            Fixture("&lt;tag&gt; &quot;quoted&quot; &#39;x&#39;", "<tag> \"quoted\" 'x'"),
            Fixture("<p>a&nbsp; b</p><p>c\t d</p>", "a b\nc d"),
            Fixture("<pre> a\n b\t c </pre>", "a\nb c"),
            Fixture("<textarea>a\n b</textarea>", "a\nb"),
            Fixture("<p>😀 𐐀 e\u0301 👩‍💻</p>", "😀 𐐀 e\u0301 👩‍💻"),
        )
        fixtures.forEachIndexed { index, fixture -> exact(fixture, "literal fixture $index") }
    }

    @Test fun headwordRemovalUsesOnlyTheFirstNonblankLineAndKeepsASingleLine() {
        val fixtures = listOf(
            Fixture("alpha", "alpha", "ALPHA"),
            Fixture("<br><p>ALPHA</p><br>\n", "ALPHA", "alpha"),
            Fixture("<b>ALPHA</b><br>meaning", "meaning", "alpha"),
            Fixture("<p>（ＡＬＰＨＡ）</p><p>meaning</p>", "meaning", "alpha"),
            Fixture("<p>alpha</p><p>alpha</p>", "alpha", "alpha"),
            Fixture("<p>alpha</p><p>alpha</p><p>meaning</p>", "alpha\nmeaning", "alpha"),
            Fixture("<p>beta</p><p>alpha</p><p>meaning</p>", "beta\nalpha\nmeaning", "alpha"),
            Fixture("<p>alpha</p><p>meaning</p>", "alpha\nmeaning"),
            // Punctuation removal exposes spaces; the old normalizer does not trim again.
            Fixture("<p>' ALPHA '</p><p>meaning</p>", "' ALPHA '\nmeaning", "alpha"),
            Fixture("<p>()</p><p>meaning</p>", "meaning", ""),
            Fixture("<p>ffi</p><p>meaning</p>", "meaning", "ﬃ"),
            Fixture("<p>İ</p><p>meaning</p>", "meaning", "i\u0307"),
        )
        fixtures.forEachIndexed { index, fixture -> exact(fixture, "headword fixture $index") }
        val firstLines = listOf("alpha", "' ALPHA '", "\"alpha\"", "(alpha)", "ＡＬＰＨＡ", "İ", "ß", "a&nbsp;b", "()", "😀")
        val headwords = listOf(null, "", "alpha", "ALPHA", " alpha ", "' alpha '", "i\u0307", "SS", "a b", "😀")
        for ((lineIndex, first) in firstLines.withIndex()) for ((wordIndex, word) in headwords.withIndex()) {
            equivalent("<div>$first</div><div>second line</div>", word, "headword matrix $lineIndex/$wordIndex")
        }
    }

    @Test fun kingsoftFontDecodingOrderAndFaceMatchingHaveLiteralExpectedResults() {
        val fixtures = listOf(
            Fixture("<font face='Kingsoft Phonetic Plain'>rE5bQst</font>", "rəˈbʌst"),
            Fixture("<font face='Tahoma, kInGsOfT pHoNeTiC pLaIn , sans-serif'>rE5bQst</font>", "rəˈbʌst"),
            Fixture("<font face='Other'>rE5bQst</font>", "rE5bQst"),
            Fixture("<font face='Kingsoft Phonetic Plain Extra'>rE5bQst</font>", "rE5bQst"),
            Fixture("<font face=\"'Kingsoft Phonetic Plain'\">rE5bQst</font>", "rE5bQst"),
            Fixture("<font face='Kingsoft Phonetic Plain'>%</font>", "〔音标未显示〕"),
            Fixture("<font face='Kingsoft Phonetic Plain'>rə</font>", "〔音标未显示〕"),
            Fixture("<font face='Kingsoft Phonetic Plain'>r<font face='Kingsoft Phonetic Plain'>E5</font>bQst</font>", "rəˈbʌst"),
            Fixture("<font face='Kingsoft Phonetic Plain'>r<span>E5</span>bQst</font>", "rəˈbʌst"),
            Fixture("<font face='Other'>r<font face='Kingsoft Phonetic Plain'>E5</font>bQst</font>", "rəˈbQst"),
            Fixture("<b>robust</b><hr><font face='Kingsoft Phonetic Plain'>rE5bQst</font><p>first sense</p><div>second sense</div>", "rəˈbʌst\nfirst sense\nsecond sense", "robust"),
        )
        fixtures.forEachIndexed { index, fixture -> exact(fixture, "phonetic fixture $index") }
        val contents = listOf(
            "AB CDEF IJLN OQTVWZ\\ 57:41689", "(),-./;'", "abc\tdef\n ghi", "", "rE5bQst",
            "<font face='Kingsoft Phonetic Plain'>E5</font>", "E<br>5", "<p>E</p><p>5</p>",
            "<font face='Other'>E5</font>", "<!-- comment -->E5", "&amp;", "&#69;&#53;", "😀", "E\u0000Q",
        )
        val faces = listOf("Kingsoft Phonetic Plain", " KINGSOFT PHONETIC PLAIN ", ",Kingsoft Phonetic Plain,", "Other, Kingsoft Phonetic Plain", "Other", "Kingsoft Phonetic Plain,Kingsoft Phonetic Plain")
        for ((contentIndex, content) in contents.withIndex()) for ((faceIndex, face) in faces.withIndex()) {
            equivalent("<div>before<font face='$face'>$content</font>after</div>", null, "phonetic matrix $contentIndex/$faceIndex")
        }
    }

    @Test fun removedElementsAndTheirNestedFontsCannotLeakIntoVisibleDefinitions() {
        val tags = listOf("script", "style", "iframe", "object", "embed", "img", "audio", "video", "link")
        val contents = listOf(
            "hidden", "<font face='Kingsoft Phonetic Plain'>rE5bQst</font>",
            "<p>hidden</p><br><div>also hidden</div>", "<font face='Kingsoft Phonetic Plain'>%</font>",
        )
        for ((tagIndex, tag) in tags.withIndex()) for ((contentIndex, content) in contents.withIndex()) {
            // Void-element closing tags and fallback content intentionally exercise HTML parsing.
            val inner = "<$tag src='https://invalid.test/pixel'>$content</$tag>"
            equivalent("<div>before$inner after</div>", null, "removed child $tagIndex/$contentIndex")
            equivalent("<font face='Kingsoft Phonetic Plain'>E$inner Q</font>", null, "font around removed child $tagIndex/$contentIndex")
        }
        exact(Fixture("before<script>hidden</script><style>hidden</style><iframe>hidden</iframe><object>hidden</object><audio>hidden</audio><video>hidden</video>after", "beforeafter"), "removed containers")
        exact(Fixture("before<img src='x'><embed src='x'><link href='x'>after", "beforeafter"), "removed void elements")
        exact(Fixture("before<object><font face='Kingsoft Phonetic Plain'>rE5bQst</font></object>after", "beforeafter"), "removed phonetic font")
    }

    @Test fun exactBlockSetNestedStructuresAndMalformedHtmlMatchTheFrozenParser() {
        val tags = listOf(
            "p", "div", "li", "section", "article", "blockquote", "h1", "h2", "h3", "h4", "hr", "tr",
            "h5", "h6", "pre", "aside", "main", "header", "footer", "address", "summary", "details",
            "ul", "ol", "dl", "dt", "dd", "table", "tbody", "td", "span", "b", "em", "wbr", "custom-tag",
        )
        tags.forEachIndexed { index, tag ->
            equivalent("left<$tag>inside<br>next</$tag>right", null, "block-set $index")
            equivalent("<div>outer<$tag><p>deep</p>end</$tag>tail</div>", "outer", "nested block-set $index")
        }
        val fragments = listOf(
            "<p>not closed<div>nested<p>last", "<b><i>first</b>middle</i>last", "<div><div><br></div>tail",
            "<ul><li>one<li>two<ul><li>nested</li></ul></ul>",
            "<table>before<tr><td><p>inside</p>tail</td></tr>after</table>",
            "<table><tr><td>first<td>second<tr><td>last", "<tr><td>orphan</td></tr><div>after</div>",
            "<p title='not closed>alpha", "<div title=\"a>b\">x</div>tail", "<<p>alpha</div>>beta",
            "<form>start<form>nested</form>end", "<select><option>a<option>b</select>after",
            "<template><p>template</p></template>after", "<plaintext><div>literal</div>",
            "<p/><div/>tail", "<font face='Kingsoft Phonetic Plain'><div>E5<p>Q</font>tail",
        )
        val wrappers = listOf("" to "", "<div>" to "</div>", "<table><tr><td>" to "</td></tr></table>", "<font face='Other'>" to "</font>")
        for ((fragmentIndex, fragment) in fragments.withIndex()) for ((wrapperIndex, wrapper) in wrappers.withIndex()) {
            equivalent(wrapper.first + fragment + wrapper.second, "alpha", "malformed $fragmentIndex/$wrapperIndex")
        }
    }

    @Test fun entitiesCommentsXmlCdataAndTextOnlyElementsKeepTheirParserSemantics() {
        val fragments = listOf(
            "&nbsp;&#160;&#xA0;&amp;&lt;&gt;&quot;&apos;&#x1F600;&#128512;",
            "a &unknown; b &notit; c &#0; d &#xD800; e &#x110000; f &amp no-semicolon",
            "a<!-- <p>hidden</p><font face='Kingsoft Phonetic Plain'>E</font> -->b",
            "a<!-- unclosed", "a<![CDATA[<div>value</div>]]>b", "<!DOCTYPE html><p>visible</p>",
            "<?xml version='1.0'?><entry><definition>first</definition><definition>second</definition></entry>",
            "<svg><text>vector</text><![CDATA[raw < > text]]></svg>tail",
            "<math><mi>alpha</mi><annotation-xml encoding='text/html'><p>note</p></annotation-xml></math>",
            "<pre>  a\n\n b\t  c\r\n d</pre>tail", "<textarea> a\n <p>literal</p>&amp; b </textarea>tail",
            "<noscript><p>fallback</p><br>next</noscript>tail", "<xmp><p>literal</p>&amp;</xmp>tail",
            "<title>title &amp; value</title><p>body</p>", "<style><!-- <p>hidden</p> --></style>tail",
        )
        fragments.forEachIndexed { index, fragment ->
            equivalent(fragment, null, "parser context $index")
            equivalent("<div>alpha</div>$fragment", "ALPHA", "parser context with heading $index")
        }
    }

    @Test fun whitespaceAndSupplementaryUnicodeAreComparedAsCompleteStrings() {
        val spaces = listOf(
            " ", "\t", "\r", "\n", "\r\n", "\u000b", "\u000c", "\u001c", "\u001d", "\u001e", "\u001f",
            "\u0000", "\u0085", "\u00a0", "\u1680", "\u2002", "\u2007", "\u200b", "\u2028", "\u2029", "\u202f", "\u205f", "\u2060", "\u3000", "\ufeff",
        )
        for ((index, space) in spaces.withIndex()) {
            equivalent("$space<p>${space}a${space}b$space</p>$space<div>$space</div>${space}c$space", null, "whitespace $index")
            equivalent("<pre>${space}a${space}b$space</pre><textarea>$space</textarea>", null, "preserved whitespace $index")
        }
        val words = listOf("😀", "👩‍💻", "👨‍👩‍👧‍👦", "👍🏽", "🇨🇳🇬🇧", "e\u0301", "𐐀𐐨", "中文", "क्‍ष", "العَرَبِيَّة", "\ud800", "\udc00", "a\ud800b\udc00c")
        words.forEachIndexed { index, word ->
            equivalent("<p>$word</p><br><span>$word</span><div>tail</div>", word, "Unicode $index")
        }
    }

    @Test fun truncationIsAppliedAfterLineCleaningAndHeadwordRemovalInUtf16Units() {
        for (length in listOf(59_998, 59_999, 60_000, 60_001, 60_002, 61_000)) {
            val body = "x".repeat(length)
            exact(Fixture("<p>$body</p>", body.take(60_000)), "length $length")
            exact(Fixture("<p>alpha</p><p>$body</p>", body.take(60_000), "alpha"), "drop before length $length")
        }
        exact(Fixture("${"a".repeat(59_999)}<br>tail", "a".repeat(59_999) + "\n"), "newline at cap")
        exact(Fixture("${"a".repeat(59_998)}😀z", "a".repeat(59_998) + "😀"), "surrogate pair ends at cap")
        // Preserve the existing code-unit cap, including its split-surrogate behavior.
        exact(Fixture("${"a".repeat(59_999)}😀z", "a".repeat(59_999) + "\ud83d"), "surrogate pair crosses cap")
        val hugeHeadword = "A".repeat(60_010)
        exact(Fixture("<p>$hugeHeadword</p><p>meaning</p>", "meaning", hugeHeadword), "long headword removed before cap")
        equivalent("<p>${"x &amp; &nbsp;\t ".repeat(12_000)}</p><div>end</div>", "x", "entities and collapse before cap")
        equivalent("<div>${"<p>${"a".repeat(50)}</p><br>\n".repeat(1_201)}</div>", "a", "many lines before cap")
    }

    @Test fun seededHtmlCombinationsPreserveCompleteOutputs() {
        val random = Random(0x4D44583120L)
        val atoms = listOf(
            "alpha", "ALPHA", "（ＡＬＰＨＡ）", "' ALPHA '", "rE5bQst", "%", "", " ", "a\t  b",
            "a\r\nb\rc", "a&nbsp;&nbsp;b", "&amp;&lt;&#x1F600;", "a\u000bb\u000cc", "a\u001cb", "a\u00a0b",
            "😀 𐐀 e\u0301", "<!-- hidden -->", "<![CDATA[<p>raw</p>]]>", "&unknown; &#0;", "a\u2028b", "a\u0000b",
        )
        val tags = listOf("p", "div", "section", "article", "li", "blockquote", "h1", "h4", "h5", "span", "b", "pre", "textarea", "noscript", "object", "audio", "script", "style", "template", "custom-tag")
        val faces = listOf("Kingsoft Phonetic Plain", "Other", "Tahoma, KINGSOFT PHONETIC PLAIN", "Kingsoft Phonetic Plain Extra")
        fun fragment(depth: Int): String {
            if (depth == 0) return atoms[random.nextInt(atoms.size)]
            return when (random.nextInt(8)) {
                0 -> atoms[random.nextInt(atoms.size)]
                1 -> "${fragment(depth - 1)}<br>${fragment(depth - 1)}"
                2 -> "<font face='${faces[random.nextInt(faces.size)]}'>${fragment(depth - 1)}</font>"
                3 -> "<table>before<tr><td>${fragment(depth - 1)}</td><td>${fragment(depth - 1)}</td></tr>after</table>"
                4 -> "<p>${fragment(depth - 1)}<div>${fragment(depth - 1)}</p>tail"
                5 -> "${fragment(depth - 1)}<hr><img src='x'>${fragment(depth - 1)}"
                else -> {
                    val tag = tags[random.nextInt(tags.size)]
                    val end = if (random.nextInt(5) == 0) "" else "</$tag>"
                    "<$tag data-v='a>b'>${fragment(depth - 1)}$end"
                }
            }
        }
        repeat(1_024) { sample ->
            val html = "<div>alpha</div>" + fragment(3) + fragment(2)
            equivalent(html, null, "seeded HTML $sample without headword")
            equivalent(html, "ALPHA", "seeded HTML $sample with headword")
        }
    }

    @Test fun explicitlyRequestedCompletePersonalCorpusMatchesTheFrozenText() {
        val outputPath = System.getenv("LEXICON_MDX_TEXT_CORPUS_OUTPUT").orEmpty()
        assumeTrue("Set LEXICON_MDX_TEXT_CORPUS_OUTPUT to a new report path to opt in", outputPath.isNotBlank())
        val output = File(outputPath).toPath().toAbsolutePath().normalize()
        Files.createDirectories(output.parent)
        // CREATE_NEW refuses old evidence, including a symlink, before any corpus work.
        Files.newByteChannel(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val progress = CorpusProgress()
            val started = System.nanoTime()
            fun budget() {
                if (System.nanoTime() - started > 180_000_000_000L) {
                    progress.stage = "cooperative_budget"
                    throw IllegalStateException()
                }
            }
            writeCorpusReport(channel, buildJsonObject {
                put("schema", "lexicon.mdx.complete-text.v1")
                put("status", "running")
                put("no_dictionary_text_in_report", true)
            })
            var failed = false
            try {
                progress.stage = "find_fixed_corpus"
                val candidates = listOf(File("dic"), File("data/dic")).flatMap { directory ->
                    directory.listFiles()?.filter { it.isFile && it.extension.equals("mdx", true) }.orEmpty()
                }.distinctBy { it.canonicalPath }.sortedBy { it.canonicalPath }
                val corpus = candidates.firstOrNull { corpusSha256(it, ::budget) == CORPUS_SHA256 }
                    ?: throw IllegalStateException()
                progress.corpusVerified = true
                budget()
                progress.stage = "open_reader"
                MdxReader(corpus).use { reader ->
                    val access = CorpusReaderAccess(reader)
                    progress.keyCount = access.size
                    check(access.size == 37_375 && reader.metadata.entries == 37_375L)
                    for (ordinal in 0 until access.size) {
                        progress.ordinal = ordinal
                        budget()
                        progress.stage = "raw_record"
                        val word = access.word(ordinal)
                        val rawRecord = access.rawRecord(ordinal)
                        val alias = rawRecord.startsWith("@@@LINK=")
                        if (alias) progress.aliasKeys++
                        progress.rawRecords++
                        progress.rawInputs.corpusRow(ordinal, word, rawRecord)

                        budget()
                        progress.stage = "resolve"
                        val visited = HashSet<Int>()
                        val html = access.resolve(ordinal, visited)
                        progress.visitedKeys += visited.size
                        progress.sharedInputs.corpusRow(ordinal, word, html)
                        if (html == null) {
                            progress.nullResolves++
                            if (alias) progress.nullAliases++
                        } else {
                            progress.resolvedHtml++
                            if (alias) progress.resolvedAliases++
                        }

                        // The same immutable HTML and headword go to both implementations.
                        budget()
                        progress.stage = "reference_text"
                        val expected = html?.let { V120.plainText(it, word) }
                        budget()
                        progress.stage = "production_text"
                        val actual = html?.let { MdxReader.plainText(it, word) }
                        progress.referenceOutputs.corpusRow(ordinal, word, expected)
                        progress.productionOutputs.corpusRow(ordinal, word, actual)
                        progress.stage = "compare_complete_strings"
                        // Avoid assertEquals: a failure must not print dictionary definitions.
                        if (expected != actual) throw AssertionError()
                        if (html != null) {
                            progress.comparedTexts++
                            if (actual.isNullOrEmpty()) progress.emptyTexts++
                        }
                        progress.matchedKeys++
                        budget()
                    }
                }
                progress.stage = "recheck_corpus_identity"
                check(corpusSha256(corpus, ::budget) == CORPUS_SHA256)
                check(progress.matchedKeys == 37_375)
                check(progress.comparedTexts + progress.nullResolves == 37_375)
                budget()
                progress.stage = "complete"
                progress.status = "passed"
            } catch (failure: Throwable) {
                // Keep only the class and current ordinal; no message, cause or raw input.
                progress.status = "failed"
                progress.failureType = failure.javaClass.simpleName
                failed = true
            } finally {
                try {
                    writeCorpusReport(channel, progress.report())
                } catch (_: Throwable) {
                    failed = true
                }
            }
            if (failed) throw AssertionError("MDX corpus comparison failed at ordinal ${progress.ordinal}")
        }
    }

    private class CorpusReaderAccess(private val reader: MdxReader) {
        private val keys = MdxReader::class.java.getDeclaredField("keys").apply { isAccessible = true }.get(reader) as List<*>
        private val wordField = requireNotNull(keys.firstOrNull()).javaClass.getDeclaredField("word").apply { isAccessible = true }
        private val offsetField = requireNotNull(keys.firstOrNull()).javaClass.getDeclaredField("offset").apply { isAccessible = true }
        private val ends = MdxReader::class.java.getDeclaredField("keyEndOffsets").apply { isAccessible = true }.get(reader) as LongArray
        private val charset = MdxReader::class.java.getDeclaredField("charset").apply { isAccessible = true }.get(reader) as Charset
        private val resolveMethod = MdxReader::class.java.getDeclaredMethod(
            "resolve", Int::class.javaPrimitiveType, MutableSet::class.java, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        private val recordMethod = MdxReader::class.java.getDeclaredMethod(
            "record", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val size: Int get() = keys.size
        fun word(ordinal: Int): String = wordField.get(keys[ordinal]) as String
        fun rawRecord(ordinal: Int): String =
            (recordMethod.invoke(reader, offsetField.getLong(keys[ordinal]), ends[ordinal]) as ByteArray)
                .toString(charset).trimEnd('\u0000').trim()
        fun resolve(ordinal: Int, visited: MutableSet<Int>): String? = resolveMethod.invoke(reader, ordinal, visited, 0) as String?
    }

    private inner class CorpusProgress {
        var status = "running"
        var stage = "initializing"
        var ordinal = -1
        var keyCount = 0
        var rawRecords = 0
        var resolvedHtml = 0
        var nullResolves = 0
        var aliasKeys = 0
        var resolvedAliases = 0
        var nullAliases = 0
        var visitedKeys = 0L
        var comparedTexts = 0
        var emptyTexts = 0
        var matchedKeys = 0
        var corpusVerified = false
        var failureType: String? = null
        val rawInputs = MessageDigest.getInstance("SHA-256")
        val sharedInputs = MessageDigest.getInstance("SHA-256")
        val referenceOutputs = MessageDigest.getInstance("SHA-256")
        val productionOutputs = MessageDigest.getInstance("SHA-256")

        fun report(): JsonObject = buildJsonObject {
            put("schema", "lexicon.mdx.complete-text.v1")
            put("status", status)
            put("scope", "host JVM complete-string compatibility; not Android performance")
            put("reference", "independently frozen 1.20 plainText, normalization and Kingsoft decoding")
            put("cooperative_budget_seconds", 180)
            put("corpus_sha256", CORPUS_SHA256)
            put("corpus_identity_verified", corpusVerified)
            put("expected_key_count", 37_375)
            put("key_count", keyCount)
            put("raw_record_count", rawRecords)
            put("resolved_html_count", resolvedHtml)
            put("null_resolve_count", nullResolves)
            put("alias_key_count", aliasKeys)
            put("resolved_alias_count", resolvedAliases)
            put("null_alias_count", nullAliases)
            put("resolve_visited_key_count", visitedKeys)
            put("compared_nonnull_text_count", comparedTexts)
            put("empty_text_count", emptyTexts)
            put("matched_key_count", matchedKeys)
            put("all_keys_compared", status == "passed" && matchedKeys == 37_375)
            put("reference_and_production_share_input", true)
            put("raw_record_sequence_sha256", rawInputs.hexDigest())
            put("resolved_input_sequence_sha256", sharedInputs.hexDigest())
            put("reference_output_sequence_sha256", referenceOutputs.hexDigest())
            put("production_output_sequence_sha256", productionOutputs.hexDigest())
            put("sequence_encoding", "ordinal int32 BE; word then nullable text: UTF-16 code-unit int32 length (-1 null), uint16 BE units; key order")
            put("no_dictionary_text_in_report", true)
            if (status != "passed") {
                put("failure_ordinal", ordinal)
                put("failure_stage", stage)
                failureType?.let { put("failure_type", it) }
            }
        }
    }

    private fun writeCorpusReport(channel: SeekableByteChannel, report: JsonObject) {
        val bytes = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report).toByteArray(Charsets.UTF_8)
        channel.position(0)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.truncate(bytes.size.toLong())
    }

    private fun corpusSha256(file: File, checkBudget: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkBudget()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.hexDigest()
    }

    private fun MessageDigest.corpusRow(ordinal: Int, word: String, text: String?) {
        corpusInt(ordinal)
        corpusString(word)
        corpusString(text)
    }

    private fun MessageDigest.corpusInt(value: Int) {
        update(byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()))
    }

    private fun MessageDigest.corpusString(value: String?) {
        corpusInt(value?.length ?: -1)
        if (value == null) return
        val buffer = ByteArray(8192)
        var used = 0
        for (character in value) {
            buffer[used++] = (character.code ushr 8).toByte()
            buffer[used++] = character.code.toByte()
            if (used == buffer.size) { update(buffer); used = 0 }
        }
        if (used > 0) update(buffer, 0, used)
    }

    private fun MessageDigest.hexDigest(): String = digest().joinToString("") { "%02x".format(it) }

    private companion object {
        const val CORPUS_SHA256 = "898cf9f1c4bf7a7c52669f3e25a5aa8396092b9b77287ba4b7cdc9a16fc77e73"
    }

    private data class Fixture(val html: String, val expected: String, val headword: String? = null)

    private fun exact(fixture: Fixture, label: String) {
        assertEquals("frozen $label", fixture.expected, V120.plainText(fixture.html, fixture.headword))
        assertEquals("production $label", fixture.expected, MdxReader.plainText(fixture.html, fixture.headword))
    }

    private fun equivalent(html: String, headword: String?, label: String) {
        assertEquals(label, V120.plainText(html, headword), MdxReader.plainText(html, headword))
    }

    // Frozen 1.20 oracle. Do not optimize or replace these steps with production helpers.
    private object V120 {
        private val whitespace = Regex("\\s+")
        private val inlineWhitespace = Regex("[\\t ]+")

        fun plainText(html: String, headword: String?): String {
            val doc = Jsoup.parseBodyFragment(html)
            doc.select("font[face]").filter { element ->
                element.attr("face").split(',').any { it.trim().equals("Kingsoft Phonetic Plain", ignoreCase = true) }
            }.forEach { element -> element.text(decode(element.wholeText()) ?: "〔音标未显示〕") }
            doc.select("script,style,iframe,object,embed,img,audio,video,link").remove()
            doc.select("br").forEach { it.after(TextNode("\n")) }
            doc.select("p,div,li,section,article,blockquote,h1,h2,h3,h4,hr,tr")
                .forEach { it.before(TextNode("\n")).after(TextNode("\n")) }
            val lines = doc.body().wholeText().replace('\u00a0', ' ').lineSequence()
                .map { it.trim().replace(inlineWhitespace, " ") }.filter { it.isNotBlank() }.toList()
            val visible = if (headword != null && lines.size > 1 && normalize(lines.first()) == normalize(headword)) lines.drop(1) else lines
            return visible.joinToString("\n").take(60000)
        }

        // The unfused normalization chain is independent of 1.20's ASCII optimization.
        private fun normalize(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
                .trim().trim('"', '\'', '.', ',', ':', ';', '!', '?', '(', ')').replace(whitespace, " ")

        private val symbols = mapOf(
            'A' to "æ", 'B' to "ɑ", 'C' to "ɔ", 'D' to "ã", 'E' to "ə", 'F' to "ʃ",
            'I' to "ɪ", 'J' to "ʊ", 'L' to "ɚ", 'N' to "ŋ", 'O' to "œ", 'Q' to "ʌ",
            'T' to "ð", 'V' to "ʒ", 'W' to "θ", 'Z' to "ɛ", '\\' to "ɜ",
            '5' to "ˈ", '7' to "ˌ", ':' to "ː", '4' to ":", '1' to "…", '6' to "!", '8' to "?", '9' to "/",
        )

        private fun decode(encoded: String): String? = buildString {
            for (character in encoded) {
                val converted = symbols[character]
                when {
                    converted != null -> append(converted)
                    character in 'a'..'z' || character.isWhitespace() || character in "(),-./;'" -> append(character)
                    else -> return null
                }
            }
        }
    }
}
