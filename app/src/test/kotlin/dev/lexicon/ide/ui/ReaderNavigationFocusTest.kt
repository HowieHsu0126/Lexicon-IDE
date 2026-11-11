package dev.lexicon.ide.ui

import androidx.compose.runtime.saveable.SaverScope
import dev.lexicon.domain.ArticleBody
import dev.lexicon.domain.ParagraphFocus
import org.junit.Assert.*
import org.junit.Test

class ReaderNavigationFocusTest {
    private val text = "🌍 Evidence needs context.\n\nEvidence can also qualify an earlier claim."
    private val body = ArticleBody("article", text, "verified-body", 1, Long.MAX_VALUE, "Test fixture")
    private val paragraphs = ParagraphFocus.paragraphs(text)
    private val scope = object : SaverScope { override fun canBeSaved(value: Any) = value is String }

    private fun display() = ReaderDisplay(
        jumpParagraph = 1, jumpVersion = 7, jumpBodyHash = body.contentHash,
        jumpStart = paragraphs[1].start, jumpEnd = paragraphs[1].start + "Evidence".length,
    )

    @Test fun anOriginalRangeRetainsItsExactOccurrenceAndVersionIdentity() {
        val request = requireNotNull(ReaderNavigationRequest.from(body.articleId, body, display()))
        assertEquals(1, request.paragraphIndex)
        assertEquals(paragraphs[1].start, request.start)
        assertEquals("Evidence", text.substring(requireNotNull(request.start), requireNotNull(request.end)))
        assertNotEquals(request, ReaderNavigationRequest.from(body.articleId, body, display().copy(jumpVersion = 8)))
    }

    @Test fun characterRangesRequireTheExactSnapshotAndValidUtf16Boundaries() {
        val invalid = listOf(
            display().copy(jumpBodyHash = null),
            display().copy(jumpBodyHash = "another-body"),
            display().copy(jumpStart = null),
            display().copy(jumpEnd = text.length + 1),
            display().copy(jumpParagraph = 0),
            display().copy(jumpParagraph = 0, jumpStart = 1, jumpEnd = 3), // Inside the emoji surrogate pair.
            display().copy(jumpEnd = display().jumpStart),
        )
        invalid.forEach { assertNull(ReaderNavigationRequest.from(body.articleId, body, it)) }
        assertNull(ReaderNavigationRequest.from(body.articleId, null, display()))
    }

    @Test fun paragraphOnlyNavigationNeedsNoArtificialSelectionButRejectsMissingParagraphs() {
        val request = ReaderNavigationRequest.from(body.articleId, body, ReaderDisplay(jumpParagraph = 1, jumpVersion = 2))
        assertNotNull(request)
        assertNull(request?.start)
        assertNull(request?.end)
        assertNull(ReaderNavigationRequest.from(body.articleId, body, ReaderDisplay(jumpParagraph = paragraphs.size)))
        assertNull(ReaderNavigationRequest.from(body.articleId, body, ReaderDisplay(jumpParagraph = -1)))
    }

    @Test fun savedDecisionsKeepTheirScopeAndReleasedStateWithoutSavingArticleText() {
        val request = requireNotNull(ReaderNavigationRequest.from(body.articleId, body, display()))
        for (phase in ReaderNavigationPhase.entries) {
            val state = ReaderNavigationFocus(request, phase)
            val encoded = requireNotNull(with(ReaderNavigationFocusSaver) { scope.save(state) })
            assertEquals(state, ReaderNavigationFocusSaver.restore(encoded))
            assertFalse(encoded.contains("Evidence"))
            assertFalse(encoded.contains("earlier claim"))
        }
        assertNull(with(ReaderNavigationFocusSaver) { scope.save(null) })
    }

    @Test fun damagedSavedDecisionsAreDiscardedAsAWhole() {
        val state = ReaderNavigationFocus(requireNotNull(ReaderNavigationRequest.from(body.articleId, body, display())), ReaderNavigationPhase.RELEASED)
        val saved = requireNotNull(with(ReaderNavigationFocusSaver) { scope.save(state) })
        val damaged = listOf(
            "not json", "{}", saved.replace("\"schema\":1", "\"schema\":99"),
            saved.replace("\"RELEASED\"", "\"unknown\""),
            saved.replace("\"articleId\":\"article\"", "\"articleId\":\"\""),
            saved.replace("\"paragraph\":1", "\"paragraph\":-1"),
            saved.replace("\"start\":${state.request.start}", "\"start\":null"),
        )
        damaged.forEach { assertNotEquals(saved, it); assertNull(ReaderNavigationFocusSaver.restore(it)) }
    }

    @Test fun navigationAndLayoutMotionCannotConsumeTheUserScrollThreshold() {
        val movement = ReaderNavigationMovement(18f)
        assertFalse(movement.observeScroll(0, moving = false, owned = false))
        assertFalse(movement.observeScroll(800, moving = true, owned = true))
        assertFalse(movement.observeScroll(950, moving = false, owned = false))
        assertFalse(movement.observeScroll(960, moving = true, owned = false))
        assertTrue(movement.observeScroll(970, moving = true, owned = false))
    }

    @Test fun nestedAndIndirectObservationsDoNotCountTheSameSmallGestureTwice() {
        val movement = ReaderNavigationMovement(18f)
        assertFalse(movement.observeScroll(0, moving = false, owned = false))
        assertFalse(movement.userScroll(-11f))
        assertFalse(movement.observeScroll(11, moving = true, owned = false))
        assertFalse(movement.userScroll(Float.NaN))
        assertTrue(movement.userScroll(-8f))
    }
}
