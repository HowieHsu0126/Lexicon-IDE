package dev.lexicon.ide.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.AppSettings
import dev.lexicon.domain.Capture
import dev.lexicon.domain.ContextOrigin
import dev.lexicon.domain.SourceRef
import dev.lexicon.domain.ThemeMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic component evidence, not a MainActivity or real-publication trial.
 * Font scale is supplied through LocalDensity; device-wide font settings are untouched.
 */
@RunWith(AndroidJUnit4::class)
class CaptureInboxLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun activeAndArchivedListsKeepTheirOwnPositionAcrossSwitchesAndRestoration() {
        val active = (0 until 18).map { capture("active-$it", "Active expression $it", 100L - it) }
        val archived = (0 until 14).map { capture("archived-$it", "Archived expression $it", 100L - it) }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TestTheme(ThemeMode.DARK, 1f) {
            PendingCaptures(active, {}, {}, archivedCaptures = archived,
                onArchive = { _, _ -> error("Position checks must not organize captures") })
        } }
        settle()
        compose.onNodeWithTag("capture_inbox_active").assertIsSelected()
        compose.onNodeWithTag("pending_captures").performScrollToNode(hasTestTag("pending_capture_active-10"))
        settle()
        val activePosition = position()
        val activeTop = bounds("pending_capture_active-10").top
        assertTrue("The active list must have moved away from its beginning", activePosition > 0f)

        compose.onNodeWithTag("capture_inbox_archived").performClick()
        settle()
        assertEquals("First entry into archived starts at its beginning", 0f, position(), 1f)
        compose.onNodeWithTag("capture_inbox_count").assertIsDisplayed().assertTextEquals("14 条捕获")
        compose.onNodeWithTag("pending_capture_archived-0").assertIsDisplayed()
        compose.onNodeWithTag("pending_captures").performScrollToNode(hasTestTag("pending_capture_archived-7"))
        settle()
        val archivedPosition = position()
        val archivedTop = bounds("pending_capture_archived-7").top
        assertTrue("The archived list must have its own nonzero position", archivedPosition > 0f)

        compose.onNodeWithTag("capture_inbox_active").performClick()
        settle()
        assertEquals(activePosition, position(), 1f)
        assertEquals(activeTop, bounds("pending_capture_active-10").top, 2f)
        compose.onNodeWithTag("capture_inbox_archived").performClick()
        settle()
        assertEquals(archivedPosition, position(), 1f)
        assertEquals(archivedTop, bounds("pending_capture_archived-7").top, 2f)

        restoration.emulateSavedInstanceStateRestore()
        settle()
        compose.onNodeWithTag("capture_inbox_archived").assertIsSelected()
        assertEquals(archivedPosition, position(), 1f)
        assertEquals(archivedTop, bounds("pending_capture_archived-7").top, 2f)
        compose.onNodeWithTag("capture_inbox_active").performClick()
        settle()
        assertEquals(activePosition, position(), 1f)
        assertEquals(activeTop, bounds("pending_capture_active-10").top, 2f)
    }

    @Test fun daylightRowsRemainReadableAndActionsReachableAtTwoHundredPercent() =
        largeTypeTrial(ThemeMode.LIGHT)

    @Test fun draculaRowsRemainReadableAndActionsReachableAtTwoHundredPercent() =
        largeTypeTrial(ThemeMode.DARK)

    private fun largeTypeTrial(theme: ThemeMode) {
        val args = InstrumentationRegistry.getArguments()
        val run = (args.getString("inboxLayoutRun") ?: "local-${UUID.randomUUID()}").also {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")))
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.filesDir, "validation-1.23.0/inbox-layout/${theme.name.lowercase()}/$run")
        check(!output.exists() && output.mkdirs()) { "Use a new inboxLayoutRun to preserve earlier screenshots" }
        val report = JSONObject().put("schema", "lexicon.capture.inbox.layout.v1")
            .put("status", "started").put("theme", theme.name).put("fontScaleOverride", 2.0)
            .put("scope", "Synthetic PendingCaptures component; LocalDensity font-scale override, no real reading data or device-wide settings change")
            .put("screenshots", JSONArray())
        var completed = false
        try {
            val material = capture("layout-item", "robust evidence", 1)
            val active = mutableStateOf(listOf(material))
            val archived = mutableStateOf(emptyList<Capture>())
            val decisions = mutableListOf<Pair<String, Boolean>>()
            compose.setContent { TestTheme(theme, 2f) {
                PendingCaptures(active.value, {}, {}, archivedCaptures = archived.value,
                    onArchive = { selected, archive ->
                        decisions += selected.id to archive
                        if (archive) {
                            active.value = active.value.filterNot { it.id == selected.id }
                            archived.value = archived.value + selected
                        } else {
                            archived.value = archived.value.filterNot { it.id == selected.id }
                            active.value = active.value + selected
                        }
                    })
            } }
            settle()
            compose.onNodeWithTag("capture_archive_${material.id}").performScrollTo().assertIsEnabled().assertIsDisplayed()
            settle()
            val activeRowReport = JSONObject()
            report.put("activeRow", activeRowReport)
            checkRowGeometry(material, "capture_archive_${material.id}", "归档", activeRowReport)
            screenshot(output, report, "01-active-large-type.png")
            compose.onNodeWithTag("capture_archive_${material.id}").performClick()
            settle()
            assertTrue(active.value.isEmpty())
            compose.onNodeWithTag("capture_inbox_archived").assertIsDisplayed().performClick()
            settle()
            compose.onNodeWithTag("capture_restore_${material.id}").performScrollTo().assertIsEnabled().assertIsDisplayed()
            settle()
            val archivedRowReport = JSONObject()
            report.put("archivedRow", archivedRowReport)
            checkRowGeometry(material, "capture_restore_${material.id}", "恢复", archivedRowReport)
            screenshot(output, report, "02-archived-large-type.png")
            compose.onNodeWithTag("capture_restore_${material.id}").performClick()
            settle()
            compose.onNodeWithTag("capture_inbox_active").performClick()
            settle()
            compose.onNodeWithTag("pending_capture_${material.id}").assertIsDisplayed()
            assertEquals(listOf(material.id to true, material.id to false), decisions)
            assertEquals(listOf(material), active.value)
            assertTrue(archived.value.isEmpty())
            report.put("archiveAndRestoreCallbacksVerified", true).put("materialUnchanged", true)
            completed = true
        } catch (error: Throwable) {
            report.put("failureClass", error.javaClass.simpleName)
                .put("failureLocations", JSONArray(error.stackTrace.take(5).map { "${it.fileName}:${it.lineNumber}" }))
            runCatching { screenshot(output, report, "failure.png") }
            throw error
        } finally {
            report.put("status", if (completed) "passed" else "failed")
            File(output, "layout-report.json").writeText(report.toString(2))
        }
    }

    private fun checkRowGeometry(material: Capture, actionTag: String, actionLabel: String, report: JSONObject) {
        compose.onNodeWithTag("capture_inbox_active").assertIsDisplayed()
        compose.onNodeWithTag("capture_inbox_archived").assertIsDisplayed()
        val viewport = bounds("pending_captures")
        val row = bounds("pending_capture_${material.id}")
        val action = bounds(actionTag)
        val headword = compose.onNodeWithText(material.target, useUnmergedTree = true).assertIsDisplayed()
        val sentence = compose.onNodeWithText(material.sentence, useUnmergedTree = true).assertIsDisplayed()
        val source = compose.onNodeWithText(material.sourceRef.display(), useUnmergedTree = true).assertIsDisplayed()
        val label = compose.onNodeWithText(actionLabel, useUnmergedTree = true).assertIsDisplayed()
        val headwordBounds = headword.fetchSemanticsNode().boundsInRoot
        val sentenceBounds = sentence.fetchSemanticsNode().boundsInRoot
        val sourceBounds = source.fetchSemanticsNode().boundsInRoot
        val labelBounds = label.fetchSemanticsNode().boundsInRoot
        report.put("viewport", coordinates(viewport)).put("row", coordinates(row))
            .put("headword", coordinates(headwordBounds)).put("sentence", coordinates(sentenceBounds))
            .put("source", coordinates(sourceBounds)).put("action", coordinates(action))
            .put("actionLabel", coordinates(labelBounds))
            .put("sourceMayUseItsDeclaredSingleLineEllipsis", true)
        val textReports = JSONObject()
        report.put("textLayouts", textReports)
        val layoutsByName = linkedMapOf<String, List<TextLayoutResult>>()
        linkedMapOf("headword" to headword, "sentence" to sentence, "source" to source, "actionLabel" to label)
            .forEach { (name, node) ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                layoutsByName[name] = layouts
                textReports.put(name, JSONObject().put("count", layouts.size)
                    .put("results", JSONArray(layouts.map(::textLayoutReport))))
            }
        listOf(headwordBounds, sentenceBounds, sourceBounds, action).forEach {
            assertInside(it, row, "Row content")
            assertInside(it, viewport, "Visible row content")
        }
        assertInside(labelBounds, action, "Action label")
        assertTrue("The headword and quote must not overlap", headwordBounds.bottom <= sentenceBounds.top + 1f)
        assertTrue("The quote must not overlap the metadata row", sentenceBounds.bottom <= sourceBounds.top + 1f)
        assertTrue("Source text must stop before the action", sourceBounds.right <= action.left + 1f)
        listOf("headword", "sentence", "actionLabel").forEach { name ->
            val layouts = layoutsByName.getValue(name)
            assertTrue("$name must expose a text layout", layouts.isNotEmpty())
            layouts.forEach { assertCompleteTextWithinLayout(it, name) }
        }
        report.put("allContentsVisible", true)
            .put("nonOverlapping", true).put("headwordQuoteAndActionNotClipped", true)
    }

    private fun assertCompleteTextWithinLayout(layout: TextLayoutResult, name: String) {
        assertTrue("$name must have a nonempty layout", layout.size.width > 0 && layout.size.height > 0 && layout.lineCount > 0)
        assertTrue("$name must not overflow vertically", !layout.didOverflowHeight)
        // The paragraph can retain the parent's available width after Text wraps to its
        // content. Check the actual line bounds instead of that unused paragraph width.
        val horizontalBounds = -1f..(layout.size.width + 1f)
        val verticalBounds = -1f..(layout.size.height + 1f)
        repeat(layout.lineCount) { line ->
            assertTrue("$name line $line must not be ellipsized", !layout.isLineEllipsized(line))
            assertTrue("$name line $line must remain within its layout",
                layout.getLineLeft(line) in horizontalBounds && layout.getLineRight(line) in horizontalBounds &&
                    layout.getLineTop(line) in verticalBounds && layout.getLineBottom(line) in verticalBounds)
        }
        assertEquals("$name must lay out all input text", layout.layoutInput.text.length,
            layout.getLineEnd(layout.lineCount - 1, visibleEnd = false))
    }

    private fun textLayoutReport(layout: TextLayoutResult): JSONObject {
        val input = layout.layoutInput
        val constraints = input.constraints
        val lines = JSONArray()
        repeat(layout.lineCount) { line ->
            lines.put(JSONObject().put("index", line)
                .put("left", layout.getLineLeft(line)).put("right", layout.getLineRight(line))
                .put("top", layout.getLineTop(line)).put("bottom", layout.getLineBottom(line))
                .put("baseline", layout.getLineBaseline(line))
                .put("start", layout.getLineStart(line))
                .put("end", layout.getLineEnd(line, visibleEnd = false))
                .put("visibleEnd", layout.getLineEnd(line, visibleEnd = true))
                .put("isEllipsized", layout.isLineEllipsized(line)))
        }
        return JSONObject().put("size", JSONObject().put("width", layout.size.width).put("height", layout.size.height))
            .put("didOverflowWidth", layout.didOverflowWidth)
            .put("didOverflowHeight", layout.didOverflowHeight)
            .put("hasVisualOverflow", layout.hasVisualOverflow)
            .put("lineCount", layout.lineCount).put("lines", lines)
            .put("paragraphWidth", layout.multiParagraph.width).put("paragraphHeight", layout.multiParagraph.height)
            .put("textLength", input.text.length).put("maxLines", input.maxLines)
            .put("softWrap", input.softWrap).put("overflow", input.overflow.toString())
            .put("constraints", JSONObject().put("minWidth", constraints.minWidth).put("maxWidth", constraints.maxWidth)
                .put("minHeight", constraints.minHeight).put("maxHeight", constraints.maxHeight))
    }

    private fun assertInside(inner: Rect, outer: Rect, description: String) {
        assertTrue("$description must have a nonempty area", inner.width > 0f && inner.height > 0f)
        assertTrue("$description must remain within its parent",
            inner.left >= outer.left - 1f && inner.right <= outer.right + 1f &&
                inner.top >= outer.top - 1f && inner.bottom <= outer.bottom + 1f)
    }

    private fun screenshot(output: File, report: JSONObject, name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val target = File(output, name)
        check(!target.exists())
        try { target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        report.getJSONArray("screenshots").put(name)
    }

    private fun coordinates(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun position() = compose.onNodeWithTag("pending_captures").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun settle() { compose.mainClock.advanceTimeBy(600); compose.waitForIdle() }

    @Composable private fun TestTheme(theme: ThemeMode, fontScale: Float, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            LexiconTheme(AppSettings(theme = theme)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()
                        .testTag("capture_inbox_layout_area")) { content() }
                }
            }
        }
    }

    private fun capture(id: String, target: String, updated: Long) = Capture(
        id = id, target = target, selectedText = target, sentence = "A robust claim needs evidence.",
        contextOrigin = ContextOrigin.USER_SUPPLIED,
        sourceRef = SourceRef("A long synthetic publication title for this layout", "Synthetic source"),
        createdAt = 1, updatedAt = updated,
    )
}
