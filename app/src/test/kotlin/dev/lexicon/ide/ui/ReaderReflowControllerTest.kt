package dev.lexicon.ide.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import dev.lexicon.domain.ReadingViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests: deterministic geometry, no Android text/font or device measurement. */
class ReaderReflowControllerTest {
    private val source = ReaderReflowSource("article", "a".repeat(64), "entry:1")

    @Test fun correctionWaitsForNewGeometryAndReleasesOnlyAfterItsNewPositionIsPlaced() {
        val tracker = ReaderReflowController(source)
        val old = layout(18, 600f)
        val next = layout(24, 1000f)
        tracker.onLayout(old, 500, scrollIdle = true, allowReflow = true)
        tracker.onPositioned(old, ReadingViewport(500f, 1000f), 500, allowReflow = true)
        val request = requireNotNull(tracker.onLayout(next, 500, true, true).request)
        assertEquals(ReaderReflowAnchor(42, .4f), request.anchor)
        assertNull(tracker.beginApply(request.serial, 500, 2000, true))
        tracker.onPositioned(old, ReadingViewport(500f, 1000f), 500, true)
        assertNull(tracker.state.readyRevision)
        tracker.onPositioned(next, ReadingViewport(500f, 1000f), 500, true)
        assertNotNull(tracker.state.readyRevision)
        assertNull("A viewport sampled at a different scroll value is stale", tracker.beginApply(request.serial, 501, 2000, true))
        val target = requireNotNull(tracker.beginApply(request.serial, 500, 2000, true))
        assertEquals(800, target.scroll)
        assertNotNull(tracker.scrollFinished(request.serial, 800).request)
        assertNull(tracker.onPositioned(next, ReadingViewport(800f, 1300f), 800, true).request)
        assertNull("An unchanged geometry must not replay its correction", tracker.onLayout(next, 800, true, true).request)
    }

    @Test fun aSecondCommitKeepsTheOriginalAnchorAndUserTakeoverInvalidatesLateCallbacks() {
        val tracker = ReaderReflowController(source)
        val old = layout(18, 600f)
        val middle = layout(24, 1000f, anchorOffset = 99)
        val newest = layout(28, 1200f, anchorOffset = 120)
        tracker.onLayout(old, 500, true, true)
        tracker.onPositioned(old, ReadingViewport(500f, 1000f), 500, true)
        val first = requireNotNull(tracker.onLayout(middle, 500, true, true).request)
        tracker.onPositioned(middle, ReadingViewport(500f, 1000f), 500, true)
        assertNotNull(tracker.beginApply(first.serial, 500, 2000, true))
        val second = requireNotNull(tracker.onLayout(newest, 500, scrollIdle = false, allowReflow = true).request)
        assertTrue(second.serial > first.serial)
        assertEquals(first.anchor, second.anchor)
        tracker.scrollFinished(first.serial, 800)
        tracker.onPositioned(middle, ReadingViewport(800f, 1300f), 800, true)
        assertEquals(second, tracker.state.request)
        assertNull(tracker.state.readyRevision)
        tracker.onPositioned(newest, ReadingViewport(800f, 1300f), 800, true)
        assertNotNull(tracker.state.readyRevision)
        tracker.cancel() // A real user drag owns the next movement.
        assertNull(tracker.beginApply(second.serial, 800, 2000, true))
        assertNull(tracker.scrollFinished(second.serial, 1000).request)
    }

    @Test fun pendingExternalScrollCancelsBeforeApplyButTheFirstNewLayoutClampRemainsEligible() {
        val old = layout(18, 700f)
        val next = layout(24, 1000f, anchorOffset = 99)
        val tracker = ReaderReflowController(source)
        tracker.onLayout(old, 500, true, true)
        tracker.onPositioned(old, ReadingViewport(500f, 1000f), 500, true)
        val pending = requireNotNull(tracker.onLayout(next, 500, true, true).request)
        tracker.onPositioned(next, ReadingViewport(500f, 1000f), 500, true)
        assertNotNull(tracker.state.readyRevision)

        // No pointer cancel arrives: an accessibility/semantic scroll moves the already placed
        // target geometry before beginApply. Reusing the old request would pull 1000 back to 800.
        assertEquals("External motion must cancel a pending correction before it can pull the user back",
            ReaderReflowState(), tracker.onPositioned(next, ReadingViewport(1000f, 1500f), 1000, true))
        assertNull(tracker.beginApply(pending.serial, 1000, 5000, true))
        assertEquals(ReaderReflowState(), tracker.scrollFinished(pending.serial, 800))
        val later = requireNotNull(tracker.onLayout(layout(28, 1600f), 1000, true, true).request)
        assertEquals("The user's new placement remains usable, without reusing the cancelled token",
            ReaderReflowAnchor(99, .4f), later.anchor)
        assertTrue(later.serial > pending.serial)

        // The first placement after new text metrics may legitimately clamp ScrollState before
        // any correction. There is no earlier placement for this targetKey to compare against.
        val clamped = ReaderReflowController(source)
        val tallerOld = layout(18, 1100f)
        clamped.onLayout(tallerOld, 900, true, true)
        clamped.onPositioned(tallerOld, ReadingViewport(900f, 1400f), 900, true)
        val first = requireNotNull(clamped.onLayout(next, 900, true, true).request)
        assertEquals(first, clamped.onPositioned(next, ReadingViewport(600f, 1100f), 600, true).request)
        val target = requireNotNull(clamped.beginApply(first.serial, 600, 600, true))
        assertEquals(600, target.scroll)
        assertEquals(800f, target.unclampedScroll, 0f)
        assertTrue(target.clamped)
        assertEquals(ReaderReflowState(), clamped.scrollFinished(first.serial, 600))
    }

    @Test fun foreignSourceAndNavigationCannotHijackReflowAndDocumentBoundsClampItsTarget() {
        val tracker = ReaderReflowController(source)
        val old = layout(18, 600f)
        val next = layout(24, 1000f)
        tracker.onLayout(old, 500, true, true)
        tracker.onPositioned(old, ReadingViewport(500f, 1000f), 500, true)
        val request = requireNotNull(tracker.onLayout(next, 500, true, true).request)
        val foreign = layout(30, 1400f, identity = source.copy(bodyHash = "b".repeat(64)))
        assertEquals(request, tracker.onLayout(foreign, 500, true, true).request)
        tracker.onPositioned(foreign, ReadingViewport(500f, 1000f), 500, true)
        assertNull(tracker.state.readyRevision)
        tracker.onPositioned(next, ReadingViewport(500f, 1000f), 500, true)
        val bounded = requireNotNull(tracker.beginApply(request.serial, 500, 600, true))
        assertEquals(600, bounded.scroll)
        assertTrue(bounded.clamped)
        tracker.cancel()
        tracker.onPositioned(next, ReadingViewport(600f, 1100f), 600, true)
        val navigationLayout = layout(28, 1200f)
        assertNull("Navigation ownership must prevent a new ordinary-reading correction",
            tracker.onLayout(navigationLayout, 600, true, allowReflow = false).request)
        assertNull(tracker.scrollFinished(request.serial, 600).request)
        assertNull("A valid placement cannot request a correction while navigation owns scrolling",
            tracker.onPositioned(navigationLayout, ReadingViewport(600f, 1100f), 600, allowReflow = false).request)
        assertNull(tracker.state.readyRevision)
        // Releasing a pin by tapping a word need not scroll or produce another placement callback.
        val releasedLayout = layout(30, 1400f)
        val released = requireNotNull(tracker.onLayout(releasedLayout, 600, true, allowReflow = true).request)
        assertEquals(ReaderReflowAnchor(42, .4f), released.anchor)
        assertTrue(released.serial > request.serial)
        assertNull("New metrics must still wait for their own measured placement", tracker.state.readyRevision)
        assertNull(tracker.beginApply(released.serial, 600, 2000, true))
        assertNull("Navigation takeover clears a pending correction but keeps its valid placement",
            tracker.onPositioned(releasedLayout, ReadingViewport(600f, 1100f), 600, allowReflow = false).request)
        assertNull(tracker.beginApply(released.serial, 600, 2000, true))
        assertNotNull("After ownership releases, retained measured geometry remains a usable anchor",
            tracker.onLayout(layout(32, 1600f), 600, true, allowReflow = true).request)
    }

    private fun layout(size: Int, top: Float, anchorOffset: Int = 42, identity: ReaderReflowSource = source) =
        object : ReaderReflowLayout {
            override val source = identity
            override val key = ReaderReflowGeometryKey(TextStyle(fontSize = size.sp), 1f, 1f, 400, LayoutDirection.Ltr)
            override fun anchorIn(viewport: ReadingViewport) = ReaderReflowAnchor(anchorOffset, .4f)
            override fun lineForOffset(offset: Int) = ReaderReflowLine(top, top + 30f)
        }

    private val retainedOriginal = ReaderReflowAnchor(42, .4f)

    @Test fun completedChangesKeepTheExactOriginalWordWithoutOwningIdleScrolling() {
        val old = retentionLayout(18, 42, 42 to 700f)
        val next = retentionLayout(24, 99, 42 to 1000f, 99 to 1100f)
        val third = retentionLayout(28, 130, 42 to 1450f, 130 to 1510f)
        val fourth = retentionLayout(32, 170, 42 to 1800f, 170 to 1870f)
        val tracker = retentionPositioned(old)

        completeRetention(tracker, next, 500, retainedOriginal, 800)
        assertEquals(ReaderReflowState(), tracker.onLayout(next, 800, true, true))
        // Every correction fully settles before the next settings commit, as in the real UI.
        completeRetention(tracker, third, 800, retainedOriginal, 1250)
        completeRetention(tracker, fourth, 1250, retainedOriginal, 1600)
        assertEquals(1, old.captureCount)
        assertEquals("A new line start cannot silently replace the frozen token", 0, next.captureCount)
        assertEquals(0, third.captureCount)
        assertEquals(listOf(42), next.resolvedOffsets)
        assertEquals(listOf(42), third.resolvedOffsets)
        assertEquals(listOf(42), fourth.resolvedOffsets)
    }

    @Test fun stationaryTapForgetsTheTokenWithoutRequiringAnotherPlacement() {
        val old = retentionLayout(18, 42, 42 to 700f)
        val next = retentionLayout(24, 99, 42 to 1000f, 99 to 1100f)
        val third = retentionLayout(28, 130, 42 to 1400f, 99 to 1600f, 130 to 1610f)
        val tracker = retentionPositioned(old)
        completeRetention(tracker, next, 500, retainedOriginal, 800)

        assertEquals(ReaderReflowState(), tracker.cancel(retainPlacement = true))
        // No onPositioned callback intervenes between this tap and the following style commit.
        completeRetention(tracker, third, 800, ReaderReflowAnchor(99, .6f), 1300)
        assertEquals(1, next.captureCount)
        assertEquals(listOf(99), third.resolvedOffsets)
    }

    @Test fun idleScrollPlacementInvalidatesTheTokenWithoutDependingOnPointerEvents() {
        val old = retentionLayout(18, 42, 42 to 700f)
        val next = retentionLayout(24, 99, 42 to 1000f, 99 to 1100f)
        val third = retentionLayout(28, 130, 42 to 1400f, 99 to 1600f, 130 to 1610f)
        val tracker = retentionPositioned(old)
        completeRetention(tracker, next, 500, retainedOriginal, 800)

        // An accessibility/semantic scroll has no preceding controller.cancel() pointer callback.
        assertEquals(ReaderReflowState(), tracker.onPositioned(next, retentionViewport(900), 900, true))
        completeRetention(tracker, third, 900, ReaderReflowAnchor(99, .4f), 1400)
        assertEquals(1, next.captureCount)
    }

    @Test fun staleScrollSamplesCannotReuseACompletedToken() {
        val old = retentionLayout(18, 42, 42 to 700f)
        val next = retentionLayout(24, 99, 42 to 1000f, 99 to 1100f)
        val third = retentionLayout(28, 130, 42 to 1400f, 130 to 1100f)
        val fourth = retentionLayout(32, 170, 130 to 1500f, 170 to 1510f)
        val tracker = retentionPositioned(old)
        completeRetention(tracker, next, 500, retainedOriginal, 800)

        assertNull(tracker.onLayout(third, 801, true, true).request)
        assertEquals(ReaderReflowState(), tracker.onPositioned(third, retentionViewport(801), 801, true))
        completeRetention(tracker, fourth, 801, ReaderReflowAnchor(130, 299f / 500f), 1201)
    }

    @Test fun navigationOwnershipForgetsTheTokenWhileItsPlacedGeometryRemainsUsable() {
        val old = retentionLayout(18, 42, 42 to 700f)
        val next = retentionLayout(24, 99, 42 to 1000f, 99 to 1100f)
        val third = retentionLayout(28, 130, 42 to 1400f, 99 to 1600f, 130 to 1610f)
        val tracker = retentionPositioned(old)
        completeRetention(tracker, next, 500, retainedOriginal, 800)

        assertEquals(ReaderReflowState(), tracker.onPositioned(next, retentionViewport(800), 800, false))
        // Releasing a navigation pin can be a stationary tap with no new layout/placement.
        completeRetention(tracker, third, 800, ReaderReflowAnchor(99, .6f), 1300)
        assertEquals(1, next.captureCount)
    }

    @Test fun aTemporaryDocumentClampDoesNotChangeTheOriginalTokenOrRatio() {
        val old = retentionLayout(18, 42, 42 to 700f)
        val clamped = retentionLayout(24, 99, 42 to 1000f, 99 to 950f)
        val expanded = retentionLayout(20, 130, 42 to 900f, 130 to 950f)
        val tracker = retentionPositioned(old)

        completeRetention(tracker, clamped, 500, retainedOriginal, 600, maxScroll = 600, expectClamp = true)
        completeRetention(tracker, expanded, 600, retainedOriginal, 700)
        assertEquals(0, clamped.captureCount)
    }

    private fun retentionPositioned(old: RetentionLayout) = ReaderReflowController(source).also {
        it.onLayout(old, 500, true, true)
        it.onPositioned(old, retentionViewport(500), 500, true)
    }

    private fun completeRetention(
        tracker: ReaderReflowController,
        retentionLayout: RetentionLayout,
        currentScroll: Int,
        expectedAnchor: ReaderReflowAnchor,
        expectedScroll: Int,
        maxScroll: Int = 5000,
        expectClamp: Boolean = false,
    ) {
        val request = requireNotNull(tracker.onLayout(retentionLayout, currentScroll, true, true).request)
        assertEquals(expectedAnchor, request.anchor)
        tracker.onPositioned(retentionLayout, retentionViewport(currentScroll), currentScroll, true)
        val target = requireNotNull(tracker.beginApply(request.serial, currentScroll, maxScroll, true))
        assertEquals(expectedScroll, target.scroll)
        assertEquals(expectClamp, target.clamped)
        tracker.scrollFinished(request.serial, expectedScroll)
        assertEquals("Successful placement must release UI/coroutine ownership", ReaderReflowState(),
            tracker.onPositioned(retentionLayout, retentionViewport(expectedScroll), expectedScroll, true))
        assertNull("Completed serials cannot replay", tracker.beginApply(request.serial, expectedScroll, maxScroll, true))
    }

    private fun retentionViewport(scroll: Int) = ReadingViewport(scroll.toFloat(), scroll + 500f)

    private fun retentionLayout(size: Int, suggestedOffset: Int, vararg lineTops: Pair<Int, Float>) =
        RetentionLayout(size, suggestedOffset, lineTops.toMap())

    private inner class RetentionLayout(size: Int, private val suggestedOffset: Int, private val lineTops: Map<Int, Float>) : ReaderReflowLayout {
        override val source = this@ReaderReflowControllerTest.source
        override val key = ReaderReflowGeometryKey(TextStyle(fontSize = size.sp), 1f, 1f, 400, LayoutDirection.Ltr)
        var captureCount = 0
        val resolvedOffsets = mutableListOf<Int>()
        override fun anchorIn(viewport: ReadingViewport): ReaderReflowAnchor {
            captureCount++
            val top = requireNotNull(lineTops[suggestedOffset])
            return ReaderReflowAnchor(suggestedOffset, (top - viewport.top) / (viewport.bottom - viewport.top))
        }
        override fun lineForOffset(offset: Int): ReaderReflowLine? {
            resolvedOffsets += offset
            return lineTops[offset]?.let { ReaderReflowLine(it, it + 30f) }
        }
    }
}
