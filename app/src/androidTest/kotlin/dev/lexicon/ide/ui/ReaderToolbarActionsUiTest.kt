package dev.lexicon.ide.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.AppSettings
import dev.lexicon.domain.StudyMode
import dev.lexicon.domain.ThemeMode
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** One fixed viewport exercises both supported layouts and every real touch callback. */
@RunWith(AndroidJUnit4::class)
class ReaderToolbarActionsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allActionsStayReadableAndTappableAtNormalAndLargeType() {
        val cases = listOf(
            Case(ThemeMode.LIGHT, 1f, StudyMode.INTENSIVE),
            Case(ThemeMode.DARK, 2f, StudyMode.SKIM),
        )
        var current by mutableStateOf(cases.first())
        val calls = IntArray(4)
        var pixelsPerDp = 0f
        compose.setContent {
            val density = LocalDensity.current.density
            pixelsPerDp = density
            CompositionLocalProvider(LocalDensity provides Density(density, current.fontScale)) {
                LexiconTheme(AppSettings(theme = current.theme)) {
                    Box(Modifier.fillMaxSize().testTag("toolbar_test_viewport").padding(12.dp)) {
                        Surface(
                            Modifier.width(360.dp).testTag("toolbar_test_surface"),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = .97f),
                            shadowElevation = 3.dp,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            ReaderToolbarActions(current.mode, { calls[0]++ }, { calls[1]++ },
                                { calls[2]++ }, { calls[3]++ })
                        }
                    }
                }
            }
        }

        cases.forEachIndexed { caseIndex, case ->
            compose.runOnIdle { current = case }
            compose.waitForIdle()
            val labels = listOf("← 退出 Zen", if (case.mode == StudyMode.SKIM) "略读" else "精读", "阅读工具", "Aa")
            val surface = rawBounds(compose.onNodeWithTag("toolbar_test_surface").fetchSemanticsNode())
            val viewport = compose.onNodeWithTag("toolbar_test_viewport").fetchSemanticsNode().boundsInRoot
            val touchBounds = mutableListOf<Rect>()
            val buttonBounds = TAGS.mapIndexed { index, tag ->
                val button = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
                val rawButton = rawBounds(button)
                val touch = button.touchBoundsInRoot
                assertTrue("$tag must have width", rawButton.width > 0f)
                assertTrue("$tag must retain a 48dp layout height", rawButton.height + 1f >= 48f * pixelsPerDp)
                assertInside("$tag in surface", rawButton, surface)
                assertInside("$tag in viewport", rawButton, viewport)
                assertSameBounds("$tag must not be clipped", rawButton, button.boundsInRoot)
                assertTrue("$tag actual touch target must be at least 48dp on both axes",
                    touch.width + 1f >= 48f * pixelsPerDp && touch.height + 1f >= 48f * pixelsPerDp)
                assertInside("$tag actual touch target in surface", touch, surface)
                assertInside("$tag actual touch target in viewport", touch, viewport)
                assertInside("$tag actual touch target must cover its visible button", rawButton, touch)
                touchBounds += touch

                val label = compose.onNodeWithText(labels[index], useUnmergedTree = true).assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                val labelNode = label.fetchSemanticsNode()
                val rawLabel = rawBounds(labelNode)
                val geometry = inspectToolbarTextGeometry(labels[index], layout, rawLabel, labelNode.boundsInRoot)
                assertTrue("$tag must retain every label character inside its allocated unclipped node: $geometry",
                    geometry.completeInsideAllocatedNode)
                assertInside("$tag label in button", rawLabel, rawButton)
                assertInside("$tag label in viewport", rawLabel, viewport)
                rawButton
            }
            if (case.fontScale == 1f) {
                assertTrue("Normal type must retain one concise row", buttonBounds.all { abs(it.top - buttonBounds.first().top) <= 1f })
            } else {
                assertTrue("Large type must wrap whole actions instead of squeezing the last one",
                    buttonBounds.maxOf { it.top } - buttonBounds.minOf { it.top } > 1f)
            }
            touchBounds.forEachIndexed { index, first -> touchBounds.drop(index + 1).forEach { second ->
                assertFalse("Actual toolbar touch areas must not overlap", first.left < second.right - 1f && first.right > second.left + 1f &&
                    first.top < second.bottom - 1f && first.bottom > second.top + 1f)
            } }
            compose.onNodeWithContentDescription("阅读外观").assertExists()

            TAGS.forEachIndexed { action, tag ->
                val before = calls.copyOf()
                compose.onNodeWithTag(tag).performTouchInput { advanceEventTime(300); click(center) }
                compose.runOnIdle {
                    calls.indices.forEach { index ->
                        assertEquals("$tag must invoke only its own callback once", before[index] + if (index == action) 1 else 0, calls[index])
                    }
                }
            }
            compose.runOnIdle { calls.forEach { assertEquals(caseIndex + 1, it) } }
        }
    }

    @Test fun textGeometryRejectsZeroWidthClippedAndEllipsizedLabels() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                LexiconTheme(AppSettings(theme = ThemeMode.LIGHT)) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Box(Modifier.width(0.dp).height(60.dp)) {
                            Text("Aa", Modifier.width(0.dp).testTag("zero_width_label"))
                        }
                        Box(Modifier.width(40.dp).height(60.dp).clipToBounds()) {
                            Text("阅读工具", Modifier.requiredWidth(180.dp).testTag("clipped_label"))
                        }
                        Text("阅读工具与外观设置", Modifier.width(64.dp).testTag("ellipsized_label"),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        compose.waitForIdle()
        val zero = observeControl("zero_width_label", "Aa")
        assertFalse("A real zero-width Text allocation must be rejected", zero.allocationNonempty)
        assertFalse(zero.completeInsideAllocatedNode)

        val clippedNode = compose.onNodeWithTag("clipped_label", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("The negative control must actually clip the Text node",
            rawBounds(clippedNode).width > clippedNode.boundsInRoot.width + 1f)
        val clipped = observeControl("clipped_label", "阅读工具")
        assertFalse("Raw/clipped node disagreement must be rejected", clipped.nodeUnclipped)
        assertFalse(clipped.completeInsideAllocatedNode)

        val ellipsis = observeControl("ellipsized_label", "阅读工具与外观设置")
        assertTrue("The negative control must actually ellipsize its label", ellipsis.lines.any { it.ellipsized })
        assertFalse("Ellipsis cannot count as complete visible text", ellipsis.noEllipsis)
        assertFalse(ellipsis.completeInsideAllocatedNode)
    }

    private fun observeControl(tag: String, expectedText: String): ToolbarTextGeometry {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val semantics = node.fetchSemanticsNode()
        return inspectToolbarTextGeometry(expectedText, layouts.single(), rawBounds(semantics), semantics.boundsInRoot)
    }

    private data class Case(val theme: ThemeMode, val fontScale: Float, val mode: StudyMode)
    private fun rawBounds(node: SemanticsNode): Rect {
        val origin = node.positionInRoot
        return Rect(origin.x, origin.y, origin.x + node.size.width, origin.y + node.size.height)
    }
    private fun assertInside(label: String, inner: Rect, outer: Rect) = assertTrue(label,
        inner.left >= outer.left - 1f && inner.top >= outer.top - 1f &&
            inner.right <= outer.right + 1f && inner.bottom <= outer.bottom + 1f)
    private fun assertSameBounds(label: String, a: Rect, b: Rect) = assertTrue(label,
        abs(a.left - b.left) <= 1f && abs(a.top - b.top) <= 1f && abs(a.right - b.right) <= 1f && abs(a.bottom - b.bottom) <= 1f)

    private companion object {
        val TAGS = listOf("reader_toolbar_exit", "reader_toolbar_mode", "reader_toolbar_tools", "reader_toolbar_appearance")
    }
}
