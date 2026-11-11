package dev.lexicon.ide.ui

import java.util.regex.Pattern
import org.junit.Assert.*
import org.junit.Test

/** All dictionary-looking text below is self-authored. No real dictionary definitions are bundled. */
class DictionaryEntryPreviewTest {
    private val word = "terra"
    private val heading = "terra¹ / təːrə ; tɛrə /"
    private val caption = "1. THE SURROUNDING AREA 周围地区, "
    private val meaning = "the land around a small home, including its garden."
    private fun entry(newline: String = "\n", body: String = meaning) = "$heading${newline}◙noun${newline}$caption$body${newline}• 自编的补充说明。"
    private fun plainEntry(body: String = meaning, head: String = heading, part: String = "◙adj", newline: String = "\n") =
        "$head$newline$part${newline}1. $body${newline}• 自编的补充说明。"

    @Test fun recognizedInlineSenseBeginsAtTheExactOriginalMeaningAndKeepsAllDetail() {
        val source = entry(); val expectedStart = heading.length + "\n◙noun\n".length + caption.length
        val result = DictionaryEntryPreview.select(word, source)
        assertEquals(DictionaryPreviewBasis.FIRST_SENSE, result.basis)
        assertEquals(expectedStart, result.start)
        assertTrue(result.text.startsWith(meaning)); assertTrue(result.truncated)
        assertRange(source, result)
        assertEquals(source, DictionaryPresentation.blocks(source).joinToString("") { it.text })
    }

    @Test fun tightTextBudgetPrioritizesMeaningWithoutClaimingToMeasureFontLayout() {
        val source = entry()
        val old = DictionaryPresentation.preview(source, maxCharacters = 28, maxLines = 1)
        val result = DictionaryEntryPreview.select(word, source, maxCharacters = 28, maxLines = 1)
        assertFalse(old.text.contains("the land"))
        assertEquals(meaning.take(28), result.text)
        assertRange(source, result)
    }

    @Test fun firstNounSenseIsNotReplacedByAShorterLaterVerbSenseOrSecondMeaning() {
        val source = entry() + "\n2. ANOTHER AREA 其他地区, a second meaning.\nterra² / təːrə /\n◙verb\n1. TO CHANGE 改变, a shorter verb meaning."
        val result = DictionaryEntryPreview.select(word, source, maxLines = 1)
        assertEquals(meaning, result.text)
        assertEquals(source.indexOf(meaning), result.start)
        assertRange(source, result)
    }

    @Test fun missingFirstSenseNeverJumpsAcrossAnotherPartOfSpeechOrSense() {
        listOf(
            "$heading\n◙noun\n1. A CAPTION 标题\n◙verb\n1. TO CHANGE 改变, a later meaning.",
            "$heading\n◙noun\n1. A CAPTION 标题\n2. NEXT CAPTION 后义, a later meaning.",
            "$heading\n◙noun\n2. NEXT CAPTION 后义, a later meaning.",
            "$heading\n◙noun\n◙verb\n$caption$meaning",
        ).forEach(::assertPrefix)
    }

    @Test fun unrecognizedAndAlreadyBodyFirstLayoutsKeepTheOldPrefix() {
        listOf("", " \t\r\n", meaning, "/ təːrə /\nnoun\n$caption$meaning", "noun\n$caption$meaning",
            "terra", "$heading\n◙noun", "$heading\n◇noun\n$caption$meaning", "$heading\nnoun used in a sentence\n$caption$meaning",
            "terra / https://example.invalid /\n◙noun\n$caption$meaning", "terra and/or another term\n◙noun\n$caption$meaning",
            "$heading\n◙noun\n1. A TITLE\n$meaning",
            "$heading\n◙noun\n1. NASA IS AN AGENCY.", "$heading\n◙noun\n1. GDP", "$heading\n◙noun\n2024 was a year.",
            "$heading\n◙noun\n1. A unit used to measure volume.", "$heading\n◙noun\n3.14 is a number.",
            "$heading\n◙noun\n1. Mixed CASE 中文, the next words.", "$heading\n◙noun\n1. NASA IS AN AGENCY. 机构, more words.",
        ).forEach(::assertPrefix)
    }

    @Test fun qualifiersAndAmbiguousContinuationsAreNotSilentlyDiscarded() {
        listOf(
            "$heading\n◙noun [countable]\n$caption$meaning",
            "$heading\n◙noun\n${caption}[countable] $meaning",
            "$heading\n◙noun\n${caption}formal\n$meaning",
            "$heading\n◙noun\n${caption}»an example only",
            "$heading\n◙noun\n${caption}2. a later meaning",
            "$heading\n◙noun\n${caption}The beginning is ambiguous.",
        ).forEach(::assertPrefix)
    }

    @Test fun comparisonRequiresTheWholeHeadwordAndDoesNotRemoveOrdinaryDigits() {
        assertPrefix(entry().replace("terra¹", "terrarium¹"))
        val source = entry().replace("terra¹", "vitamin B12¹")
        assertEquals(DictionaryPreviewBasis.FIRST_SENSE, DictionaryEntryPreview.select("vitamin B12", source).basis)
        assertEquals(DictionaryPreviewBasis.PREFIX, DictionaryEntryPreview.select("vitamin B", source).basis)
    }

    @Test fun plainPartOfSpeechIsSupportedWithoutGeneralizingUnknownMarkers() {
        val source = entry().replace("◙noun", "noun")
        assertEquals(DictionaryPreviewBasis.FIRST_SENSE, DictionaryEntryPreview.select(word, source).basis)
        assertPrefix(entry().replace("◙noun", "◙◙noun"))
    }

    @Test fun allSupportedLineEndingsAndWhitespaceKeepExactSourceRanges() {
        for (newline in listOf("\n", "\r\n", "\r", "\u0085", "\u2028", "\u2029")) {
            val body = "the\u00a0land  around\ta home."
            val source = " \t${newline}${entry(newline, body)}"
            val result = DictionaryEntryPreview.select(word, source, maxLines = 2)
            assertEquals(source.indexOf(body), result.start)
            assertTrue(result.text.startsWith(body + newline))
            assertRange(source, result)
        }
    }

    @Test fun selectedRangeDoesNotCutCrlfAtTheCharacterBudget() {
        val source = entry("\r\n", "a")
        val result = DictionaryEntryPreview.select(word, source, maxCharacters = 2)
        assertEquals("a", result.text); assertRange(source, result)
    }

    @Test fun graphemeBudgetsPreserveAccentsSurrogatesFlagsAndJoinedEmoji() {
        for (cluster in listOf("e\u0301", "a😀", "a🇨🇳", "a👍🏽", "a👩‍💻")) {
            val source = entry(body = cluster + " next")
            for (limit in 1..cluster.length + 2) assertRange(source, DictionaryEntryPreview.select(word, source, maxCharacters = limit))
        }
        val source = entry(body = "a" + "\u0301".repeat(600) + " next")
        assertPrefix(source, maxCharacters = 480)
    }

    @Test fun emptyOrMissingMeaningAfterARecognizedCaptionFallsBack() {
        assertPrefix(entry(body = ""))
        assertPrefix("$heading\n◙noun\n$caption \t")
        assertPrefix("$heading\n◙noun\n$caption\n$meaning")
    }

    @Test fun boundedRecognitionNeverAcceptsPartialLinesOrScansPastTheLineBudget() {
        val justInside = "\n".repeat(13) + entry().substringBefore("\n•")
        assertEquals(DictionaryPreviewBasis.FIRST_SENSE, DictionaryEntryPreview.select(word, justInside).basis)
        assertPrefix("\n".repeat(14) + entry())
        assertPrefix(entry(body = "a".repeat(5000)))
        assertPrefix(" ".repeat(4090) + entry())
    }

    @Test fun genericCapturePreviewAndInvalidLimitContractsAreUnchanged() {
        val source = entry()
        assertEquals(source.substringBefore("\n•"), DictionaryPresentation.preview(source, 1200, 3).text)
        for (limit in listOf(0, -1, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { DictionaryEntryPreview.select(word, "", maxCharacters = limit) }
            assertThrows(IllegalArgumentException::class.java) { DictionaryEntryPreview.select(word, "", maxLines = limit) }
        }
        val maximum = DictionaryEntryPreview.select(word, source, Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(source.substring(source.indexOf(meaning)), maximum.text)
        assertRange(source, maximum)
    }

    @Test fun headerSyllableDotsAffectOnlyComparisonAndBothSenseLayoutsStillWork() {
        for (dot in listOf('\u00b7', '\u2219')) for (head in listOf("ter${dot}ra¹ / təːrə /", "t${dot}e${dot}r${dot}r${dot}a¹ / təːrə /")) {
            val titled = entry().replace(heading, head)
            assertFirstSense(word, titled, head.length + "\n◙noun\n".length + caption.length, meaning)
            val plain = plainEntry(head = head)
            assertFirstSense(word, plain, head.length + "\n◙adj\n1. ".length, meaning)
            assertEquals(titled, DictionaryPresentation.blocks(titled).joinToString("") { it.text })
            assertEquals(plain, DictionaryPresentation.blocks(plain).joinToString("") { it.text })
        }
    }

    @Test fun literalMiddleDotsInTheEntryWordRequireExactMatchingBeforeHeaderReduction() {
        for (literal in listOf("ter·ra", "ter∙ra", "terra · firma", "terra ∙ firma")) {
            val head = "${literal}¹ / təːrə /"
            val source = plainEntry(head = head)
            assertFirstSense(literal, source, head.length + "\n◙adj\n1. ".length, meaning)
        }
        assertLookupPrefix("ter·ra", plainEntry())
        assertLookupPrefix("ter∙ra", plainEntry())
        assertLookupPrefix("terra · firma", plainEntry(head = "terra firma¹ / təːrə /"))
        assertLookupPrefix("terra ∙ firma", plainEntry(head = "terra firma¹ / təːrə /"))
    }

    @Test fun onlyLetterBoundedSupportedDotsInTheHeaderCanBeRemovedForComparison() {
        for (dot in listOf('\u00b7', '\u2219')) for ((lookupWord, displayedHead) in listOf(
            word to "ter ${dot}ra", word to "ter${dot} ra", word to "ter${dot}${dot}ra",
            word to "${dot}terra", word to "terra${dot}",
            "terra firma" to "terra ${dot} firma", "terra12" to "terra1${dot}2", "terra1" to "terra${dot}1",
        )) {
            assertLookupPrefix(lookupWord, plainEntry(head = "${displayedHead}¹ / təːrə /"))
        }
        for (dot in listOf('.', '\u2027', '\u2022', '\u22c5')) {
            assertLookupPrefix(word, plainEntry(head = "ter${dot}ra¹ / təːrə /"))
        }
    }

    @Test fun adjAndLowercaseNumberedDefinitionsExposeTheExactFirstSense() {
        // This self-authored sentence was a v1 fallback and is now an explicitly supported layout.
        val body = "the ground surrounding a house."
        for (part in listOf("adj", "◙adj", "noun", "◙noun")) {
            val source = plainEntry(body, part = part)
            assertFirstSense(word, source, heading.length + "\n$part\n1. ".length, body)
        }
        val titledAdj = entry().replace("◙noun", "◙adj")
        assertFirstSense(word, titledAdj, heading.length + "\n◙adj\n".length + caption.length, meaning)
        assertPrefix(plainEntry(part = "adj used in a sentence"))
        assertPrefix(plainEntry(part = "◙adj [formal]"))
        assertPrefix(plainEntry(part = "◇adj"))
    }

    @Test fun plainFirstSenseRequiresOneThenWhitespaceAndANonemptyLowercaseBody() {
        for ((separator, body) in listOf(" " to "a", "\t \t" to meaning)) {
            val prefix = "$heading\n◙adj\n1.$separator"
            val source = prefix + body
            assertFirstSense(word, source, prefix.length, body)
        }
        listOf(
            "1.$meaning", "2. $meaning", "01. $meaning", "1. ", "1. \t", "1. The land around a home.",
            "1. THE SURROUNDING AREA", "1. [countable] $meaning", "1. »an example only", "1. 2. $meaning",
        ).forEach { assertPrefix("$heading\n◙adj\n$it") }
    }

    @Test fun plainFirstSenseDoesNotSkipMissingBodiesOrAdvanceToAnotherSense() {
        listOf(
            "$heading\n◙adj\n1. \n$meaning",
            "$heading\n◙adj\n1. \n2. $meaning",
            "$heading\n◙adj\n1. A TITLE\n1. $meaning",
            "$heading\n◙adj\n1. \n◙verb\n1. $meaning",
            "$heading\n◙adj\n2. $meaning\n1. a later entry.",
        ).forEach(::assertPrefix)
        val source = plainEntry() + "\n2. a shorter meaning.\nterra² / təːrə /\n◙verb\n1. a later verb meaning."
        assertFirstSense(word, source, heading.length + "\n◙adj\n1. ".length, meaning)
    }

    @Test fun leadingUsageLabelsAtWholeWordOrLabelBoundariesKeepTheOriginalPrefix() {
        val labels = listOf("formal", "informal", "literary", "technical", "spoken", "written",
            "usually plural", "usually singular", "countable", "uncountable", "transitive", "intransitive")
        val suffixes = listOf("", " $meaning", "\t$meaning", ": $meaning", "; $meaning", ", $meaning",
            "($meaning)", "/$meaning", "\n$meaning")
        for (label in labels) for (suffix in suffixes) assertPrefix(plainEntry(body = label + suffix))
    }

    @Test fun longerWordsAndAlphanumericContinuationsAreNotStandaloneUsageLabels() {
        val starts = listOf("formalism", "informally", "literary2", "technicality", "spokenword", "writtenness",
            "usually plurality", "usually singularity", "countable2", "uncountably", "transitively", "intransitively")
        for (first in starts) {
            val body = "$first is the beginning of this self-authored definition."
            val source = plainEntry(body)
            assertFirstSense(word, source, heading.length + "\n◙adj\n1. ".length, body)
        }
    }

    @Test fun plainSensePreservesAllLineEndingsAndGraphemeBoundaries() {
        for (newline in listOf("\n", "\r\n", "\r", "\u0085", "\u2028", "\u2029")) {
            val body = "the\u00a0land  around\ta home."
            val head = "ter·ra¹ / təːrə /"
            val source = plainEntry(body, head, newline = newline)
            assertFirstSense(word, source, head.length + newline.length + "◙adj".length + newline.length + 3, body)
        }
        val crlf = plainEntry(body = "a", newline = "\r\n")
        val bounded = DictionaryEntryPreview.select(word, crlf, maxCharacters = 2)
        assertEquals(DictionaryPreviewBasis.FIRST_SENSE, bounded.basis)
        assertEquals("a", bounded.text); assertRange(crlf, bounded)
        for (cluster in listOf("e\u0301", "a😀", "a🇨🇳", "a👍🏽", "a👩‍💻")) {
            val source = plainEntry(body = cluster + " next")
            for (limit in 1..cluster.length + 2) assertRange(source, DictionaryEntryPreview.select(word, source, maxCharacters = limit))
        }
        assertPrefix(plainEntry(body = "a" + "\u0301".repeat(600) + " next"), maxCharacters = 480)
    }

    @Test fun plainSenseRecognitionCannotBypassCharacterOrLineScanLimits() {
        val inside = "\n".repeat(13) + plainEntry().substringBefore("\n•")
        assertFirstSense(word, inside, 13 + heading.length + "\n◙adj\n1. ".length, meaning)
        assertPrefix("\n".repeat(14) + plainEntry())
        assertPrefix(plainEntry(body = "a".repeat(5000)))
        assertPrefix(" ".repeat(4090) + plainEntry())
    }

    private fun assertFirstSense(lookupWord: String, source: String, expectedStart: Int, expectedText: String) {
        val value = DictionaryEntryPreview.select(lookupWord, source, maxLines = 1)
        assertEquals(DictionaryPreviewBasis.FIRST_SENSE, value.basis)
        assertEquals(expectedStart, value.start)
        assertEquals(expectedStart + expectedText.length, value.endExclusive)
        assertEquals(expectedText, value.text)
        assertTrue(value.truncated)
        assertRange(source, value)
    }

    private fun assertPrefix(source: String, maxCharacters: Int = 480) = assertLookupPrefix(word, source, maxCharacters)

    private fun assertLookupPrefix(lookupWord: String, source: String, maxCharacters: Int = 480) {
        val old = DictionaryPresentation.preview(source, maxCharacters)
        val actual = DictionaryEntryPreview.select(lookupWord, source, maxCharacters)
        assertEquals(DictionaryPreviewBasis.PREFIX, actual.basis)
        assertEquals(0, actual.start); assertEquals(old.text, actual.text)
        assertEquals(old.endExclusive, actual.endExclusive); assertEquals(old.truncated, actual.truncated)
        assertRange(source, actual)
    }

    private fun assertRange(source: String, value: DictionaryDefinitionPreview) {
        assertTrue(value.start in 0..value.endExclusive && value.endExclusive <= source.length)
        assertEquals(source.substring(value.start, value.endExclusive), value.text)
        val boundaries = mutableSetOf(0)
        val matcher = Pattern.compile("\\X").matcher(source)
        while (matcher.find()) boundaries += matcher.end()
        assertTrue(value.start in boundaries); assertTrue(value.endExclusive in boundaries)
    }
}
