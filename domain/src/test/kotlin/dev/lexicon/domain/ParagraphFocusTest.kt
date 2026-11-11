package dev.lexicon.domain

import org.junit.Assert.*
import org.junit.Test

class ParagraphFocusTest {
    @Test fun `paragraph ranges preserve original utf16 offsets and line breaks`() {
        val text = "  First 🧬 paragraph.\nA soft line break.\n\n\tSecond paragraph.\r\n \r\nThird.  "
        val paragraphs = ParagraphFocus.paragraphs(text)
        assertEquals(3, paragraphs.size)
        assertEquals("First 🧬 paragraph.\nA soft line break.", text.substring(paragraphs[0].start, paragraphs[0].endExclusive))
        assertEquals("Second paragraph.", text.substring(paragraphs[1].start, paragraphs[1].endExclusive))
        assertEquals("Third.", text.substring(paragraphs[2].start, paragraphs[2].endExclusive))
        assertEquals(text.indexOf("Second"), paragraphs[1].start)
    }

    @Test fun `empty paragraphs are omitted without moving source offsets`() {
        val text = "\n\n  \n\nOne.\n\n\n\nTwo.\u2029Three.\n\n"
        assertEquals(listOf("One.", "Two.", "Three."), ParagraphFocus.paragraphs(text).map { text.substring(it.start, it.endExclusive) })
        assertTrue(ParagraphFocus.paragraphs(" \n\n \t ").isEmpty())
    }

    private val bounds = listOf(
        ReadingParagraphBounds(0, 0f, 150f),
        ReadingParagraphBounds(1, 180f, 330f),
        ReadingParagraphBounds(2, 360f, 960f),
        ReadingParagraphBounds(3, 990f, 1150f),
    )

    @Test fun `focus follows viewport reading line through a long paragraph`() {
        assertEquals(0, ParagraphFocus.choose(bounds, ReadingViewport(-100f, 400f), null))
        assertEquals(1, ParagraphFocus.choose(bounds, ReadingViewport(0f, 500f), 0))
        assertEquals(2, ParagraphFocus.choose(bounds, ReadingViewport(250f, 750f), 1))
        assertEquals(2, ParagraphFocus.choose(bounds, ReadingViewport(650f, 1150f), 2))
        assertEquals(3, ParagraphFocus.choose(bounds, ReadingViewport(850f, 1350f), 2))
    }

    @Test fun `hysteresis avoids switching back and forth at a boundary`() {
        val adjacent = listOf(ReadingParagraphBounds(0, 0f, 200f), ReadingParagraphBounds(1, 220f, 450f))
        assertEquals(0, ParagraphFocus.choose(adjacent, ReadingViewport(9f, 509f), 0)) // anchor 219
        assertEquals(1, ParagraphFocus.choose(adjacent, ReadingViewport(18f, 518f), 0)) // anchor 228
        assertEquals(1, ParagraphFocus.choose(adjacent, ReadingViewport(-7f, 493f), 1)) // anchor 203
        assertEquals(0, ParagraphFocus.choose(adjacent, ReadingViewport(-19f, 481f), 1)) // anchor 191
    }

    @Test fun `scrolling near the footer still focuses the last visible paragraph`() {
        assertEquals(3, ParagraphFocus.choose(bounds, ReadingViewport(1000f, 1700f), 2))
        assertEquals(0, ParagraphFocus.choose(bounds, ReadingViewport(-450f, 50f), null))
    }

    @Test fun `offscreen paragraph cannot retain focus through hysteresis`() {
        assertEquals(2, ParagraphFocus.choose(bounds, ReadingViewport(350f, 850f), 1, hysteresis = 1000f))
    }

    @Test fun `missing or invalid geometry preserves previous focus`() {
        assertEquals(2, ParagraphFocus.choose(emptyList(), ReadingViewport(0f, 500f), 2))
        assertEquals(2, ParagraphFocus.choose(bounds, ReadingViewport(Float.NaN, 500f), 2))
        assertEquals(2, ParagraphFocus.choose(bounds, ReadingViewport(500f, 500f), 2))
        assertEquals(2, ParagraphFocus.choose(bounds, ReadingViewport(1200f, 1700f), 2))
        assertNull(ParagraphFocus.choose(emptyList(), ReadingViewport(0f, 500f), null))
    }

    @Test fun `tap offsets select the exact repeated paragraph`() {
        val text = "Same repeated phrase.\n\nSame repeated phrase."
        val paragraphs = ParagraphFocus.paragraphs(text)
        assertEquals(0, ParagraphFocus.atOffset(paragraphs, text.indexOf("repeated")))
        assertEquals(1, ParagraphFocus.atOffset(paragraphs, text.lastIndexOf("repeated")))
        assertEquals(1, ParagraphFocus.atOffset(paragraphs, text.length))
        assertNull(ParagraphFocus.atOffset(emptyList(), 0))
    }
}
