package dev.lexicon.ide.ui

import dev.lexicon.domain.DictionaryEntry
import org.junit.Assert.*
import org.junit.Test

class DictionaryPresentationTest {
    @Test fun previewIsAnUnmodifiedPrefixWithIndependentCharacterAndLineLimits() {
        val source = (1..12).joinToString("\n") { "  line $it  " }
        val preview = DictionaryPresentation.preview(source, 1200, 8)
        assertEquals(source.substringBefore("\n  line 9"), preview.text)
        assertTrue(preview.truncated)
        assertEquals(preview.text.length, preview.endExclusive)
        val bounded = DictionaryPresentation.preview("a".repeat(5000))
        assertEquals(480, bounded.endExclusive)
        assertEquals("a".repeat(480), bounded.text)
    }

    @Test fun crlfIsOneLogicalBreakAndNeitherPreviewNorBlocksSplitsIt() {
        val source = "first\r\nsecond\rthird\nfourth"
        assertEquals("first\r\nsecond", DictionaryPresentation.preview(source, 100, 2).text)
        assertEquals("first", DictionaryPresentation.preview(source, 6, 8).text)
        val blocks = DictionaryPresentation.blocks(source, 6)
        assertRoundTrip(source, blocks)
        blocks.dropLast(1).forEach { assertFalse(source[it.endExclusive - 1] == '\r' && source[it.endExclusive] == '\n') }
    }

    @Test fun blankLinesSpacesNbspAndIpaRemainUnchanged() {
        val source = " \t\r\n\r\nbook¹  / bʊk /\u00a0\n  noun\n\n末尾  "
        assertRoundTrip(source, DictionaryPresentation.blocks(source, 9))
        assertEquals(source, DictionaryPresentation.preview(source, 1200, 20).text)
        assertFalse(DictionaryPresentation.preview(source, 1200, 20).truncated)
    }

    @Test fun previewDoesNotSplitCombiningMarksFlagsSkinTonesOrJoinedEmoji() {
        val clusters = listOf("e\u0301", "🇨🇳", "👍🏽", "👩‍💻", "👨‍👩‍👧‍👦", "क्‍ष")
        clusters.take(5).forEach { cluster ->
            for (limit in 1 until cluster.length) assertEquals("", DictionaryPresentation.preview(cluster + "z", limit).text)
            assertEquals(cluster, DictionaryPresentation.preview(cluster + "z", cluster.length).text)
        }
        clusters.forEach { assertRoundTrip(it.repeat(9), DictionaryPresentation.blocks(it.repeat(9), 3)) }
    }

    @Test fun indivisibleLongClusterIsPreservedInsteadOfDroppingOrSplittingItsMarks() {
        val cluster = "a" + "\u0301".repeat(2500)
        assertEquals("", DictionaryPresentation.preview(cluster + " next").text)
        val blocks = DictionaryPresentation.blocks(cluster + " next", 1000)
        assertEquals(cluster, blocks.first().text)
        assertRoundTrip(cluster + " next", blocks)
    }

    @Test fun longUnbrokenTextIsFullyPartitionedWithContiguousOffsets() {
        val text = "z".repeat(60_000)
        val blocks = DictionaryPresentation.blocks(text)
        assertEquals(60, blocks.size)
        assertTrue(blocks.all { it.text.length == 1000 })
        assertRoundTrip(text, blocks)
    }

    @Test fun whitespaceBoundaryIsPreferredWithoutTrimmingEitherSide() {
        val source = "abcdef\nxyz abcdefghij tail"
        val blocks = DictionaryPresentation.blocks(source, 10)
        assertEquals("abcdef\n", blocks.first().text)
        assertRoundTrip(source, blocks)
        assertTrue(blocks.all { it.text.length <= 10 })
    }

    @Test fun supplementaryCharactersNeverLeaveSurrogatesAcrossBlocks() {
        val source = "A😀 B🦉 C𐐀".repeat(60)
        val blocks = DictionaryPresentation.blocks(source, 7)
        assertRoundTrip(source, blocks)
        blocks.forEach { block ->
            assertFalse(Character.isLowSurrogate(block.text.first()))
            assertFalse(Character.isHighSurrogate(block.text.last()))
        }
    }

    @Test fun everyOneOfEightEntriesAndTheirOrderAffectsIdentity() {
        val entries = (0..7).map { DictionaryEntry("word$it", "", "meaning $it") }
        val original = DictionaryPresentation.identity("source", "word", entries)
        assertEquals(original, DictionaryPresentation.identity("source", "word", entries.toList()))
        entries.indices.forEach { index ->
            val altered = entries.toMutableList().apply { this[index] = this[index].copy(definition = "changed") }
            assertNotEquals(original, DictionaryPresentation.identity("source", "word", altered))
        }
        assertNotEquals(original, DictionaryPresentation.identity("source", "word", entries.reversed()))
        assertNotEquals(original, DictionaryPresentation.identity("other", "word", entries))
        assertNotEquals(original, DictionaryPresentation.identity("source", "words", entries))
        assertNotEquals(original, DictionaryPresentation.identity("source", "word", entries + entries[0]))
    }

    @Test fun emptyAndWhitespaceDefinitionsAreNotRewritten() {
        assertEquals(emptyList<DictionaryTextBlock>(), DictionaryPresentation.blocks(""))
        assertEquals(DictionaryPreview("", 0, false), DictionaryPresentation.preview(""))
        assertEquals(" \t ", DictionaryPresentation.preview(" \t ").text)
        assertRoundTrip(" \t ", DictionaryPresentation.blocks(" \t ", 1))
    }

    @Test fun nonpositiveLimitsAreRejectedEvenForAnEmptyDefinition() {
        for (limit in listOf(0, -1, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { DictionaryPresentation.preview("", maxCharacters = limit) }
            assertThrows(IllegalArgumentException::class.java) { DictionaryPresentation.preview("", maxLines = limit) }
            assertThrows(IllegalArgumentException::class.java) { DictionaryPresentation.blocks("", limit) }
        }
        assertEquals("abc", DictionaryPresentation.preview("abc", Int.MAX_VALUE, Int.MAX_VALUE).text)
        assertRoundTrip("abc", DictionaryPresentation.blocks("abc", Int.MAX_VALUE))
    }

    private fun assertRoundTrip(source: String, blocks: List<DictionaryTextBlock>) {
        assertEquals(source, blocks.joinToString("") { it.text })
        var next = 0
        blocks.forEach { block ->
            assertEquals(next, block.start)
            assertTrue(block.endExclusive > block.start)
            assertEquals(source.substring(block.start, block.endExclusive), block.text)
            next = block.endExclusive
        }
        assertEquals(source.length, next)
    }
}
