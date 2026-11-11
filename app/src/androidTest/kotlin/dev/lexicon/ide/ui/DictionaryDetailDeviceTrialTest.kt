package dev.lexicon.ide.ui

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import dev.lexicon.ide.LexiconApplication
import dev.lexicon.ide.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import kotlin.math.abs

/** Opt-in installed-app trial. Real text appears only in screenshots, never report JSON. */
@RunWith(AndroidJUnit4::class)
class DictionaryDetailDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val codec = Json { ignoreUnknownKeys = true }

    @Test
    fun fullMdxDetailPreservesDraftsPositionAndOriginalLearningData() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Real dictionary detail trial is opt-in", args.getString("dictionaryDetailTrial") == "1")
        val variant = requireNotNull(args.getString("variant"))
        require(variant in setOf("day-font100", "night-font200"))
        val evidenceRun = args.getString("evidenceRun")
        require(evidenceRun == null || evidenceRun.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) { "evidenceRun must be a safe output name" }
        val scale = if (variant == "night-font200") 2f else 1f
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiconApplication
        val repo = app.repository
        val database = app.getDatabasePath("lexicon.sqlite")
        val variantOutput = File(app.filesDir, "validation-1.19.0/dictionary-detail/$variant")
        val output = if (evidenceRun == null) variantOutput else File(variantOutput, evidenceRun)
        check(!output.exists()) { "Preserve existing detail trial evidence; use a fresh evidence directory" }
        check(output.mkdirs())
        val reportFile = File(output, "device-trial.json")
        val version = app.packageManager.getPackageInfo(app.packageName, 0)
        val report = JSONObject().put("schema", "lexicon.dictionary.detail.trial.v1")
            .put("status", "started").put("variant", variant).put("version", version.versionName)
            .put("evidenceRun", evidenceRun ?: JSONObject.NULL)
            .put("versionCode", version.longVersionCode).put("startedAt", System.currentTimeMillis())
            .put("scope", "Existing pending captures, real installed MDX, world detail blocks and navigation; no AI or performance claim")
            .put("screenshots", JSONArray()).put("entries", JSONArray())
            .put("jsonContainsLearningText", false)
            .put("snapshotScope", "All lexicon.sqlite tables after repository initialization; normal Application startup is outside this snapshot")
        var original: Map<String, List<List<String?>>>? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var succeeded = false
        var stage = "preflight"
        try {
            check(abs(app.resources.configuration.fontScale - scale) < .02f)
            requireOfflineNoKey(app, repo)
            runBlocking { repo.initialize() }
            val snapshot = readSnapshot(database).also { original = it }
            report.put("originalTableHashes", snapshotHashes(snapshot))
                .put("originalSnapshotKeptOnlyInMemory", true)
            val captures = runBlocking { repo.captures.first() }
            val cardCaptureIds = runBlocking { repo.cards.first() }.map { it.captureId }.toSet()
            val pending = captures.filter { it.id !in cardCaptureIds && it.target.isNotBlank() }.sortedByDescending { it.updatedAt }
            val first = requireNotNull(pending.firstOrNull()) { "An existing pending capture is required" }
            val second = requireNotNull(pending.firstOrNull { it.id != first.id && !it.target.trim().equals("world", true) }) {
                "Another existing pending capture with a different target is required"
            }
            val dictionary = runBlocking { repo.dictionaryStatus() }
            check(dictionary.ready && dictionary.format == "MDX" && dictionary.id.isNotBlank())
            report.put("selectedDictionarySha256", dictionary.id)
                .put("dictionaryEntries", dictionary.entryCount)
                .put("captureIdentityHashes", JSONArray(listOf(hash(first.id), hash(second.id))))
                .put("originalCaptureHash", hash(codec.encodeToString(Capture.serializer(), first)))
                .put("originalRange", JSONObject().put("start", first.selectionStart).put("end", first.selectionEnd)
                    .put("bodyHash", first.bodyHash ?: JSONObject.NULL))
            val settings = runBlocking { repo.settings.first() }
            runBlocking { repo.updateSettings(settings.copy(
                theme = if (variant == "night-font200") ThemeMode.DARK else ThemeMode.LIGHT,
                titleClassification = false, reader = settings.reader.copy(automaticStudy = false))) }
            val trialSettings = readRows(database, "preferences").single { it[0] == "settings" }
            report.put("trialSettingsHash", hash(requireNotNull(trialSettings[1])))
            reportFile.writeText(report.toString(2))

            stage = "open_existing_capture"
            report.put("trialEditionDay", LocalDate.now().toString())
            val activity = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
            compose.waitUntil(20_000) { exists("daily_reading") }
            openPending()
            openCapture(first)
            compose.onNodeWithTag("capture_header").assertIsDisplayed()
            compose.onNodeWithTag("capture_close").assertIsDisplayed()
            stage = "edit_unsaved_draft"
            reach("capture_target").performClick().performTextReplacement("world")
            setCursor("capture_target", TARGET_CURSOR)
            reach("capture_sentence").performClick().performTextReplacement(DRAFT)
            setCursor("capture_sentence", SENTENCE_CURSOR)
            compose.waitUntil(15_000) { keyboardVisible() }
            compose.waitUntil(30_000) { dictionaryReady("world") }
            assertDraft()
            assertOriginalCapture(repo, first)
            screenshot(output, report, "01-draft-keyboard.png")
            val expected = runBlocking { repo.lookup("world") }
            check(expected.isNotEmpty() && expected.any { it.definition.length > 4_000 }) { "A real long world definition is required" }
            val preview = layout("capture_dictionary_definition_0").layoutInput.text.text
            check(preview.isNotEmpty() && expected.first().definition.startsWith(preview))
            report.put("previewCharacters", preview.length).put("previewSha256", hash(preview))
                .put("fullResultIdentity", DictionaryPresentation.identity(dictionary.id, "world", expected))

            stage = "open_detail_with_keyboard_visible"
            val opener = reach("capture_dictionary_toggle")
            check(keyboardVisible()) { "Opening detail must exercise a visible IME" }
            opener.performClick()
            awaitDetail()
            compose.waitUntil(10_000) { !keyboardVisible() }
            assertFixedDetailActions()
            report.put("imeVisibleBeforeDetail", true).put("imeHiddenAfterDetail", true)
            screenshot(output, report, "02-detail-first.png")

            stage = "verify_all_rendered_blocks"
            expected.forEachIndexed { entryIndex, entry ->
                selectEntry(entryIndex, expected.size)
                val expectedBlocks = DictionaryPresentation.blocks(entry.definition)
                check(expectedBlocks.isNotEmpty())
                val observed = StringBuilder()
                val blockReports = JSONArray()
                var previousEnd = 0
                expectedBlocks.forEachIndexed { blockIndex, block ->
                    check(block.start == previousEnd && block.endExclusive > block.start)
                    compose.onNodeWithTag("dictionary_detail_list").performScrollToIndex(blockIndex + 1)
                    val tag = "dictionary_detail_block_${block.start}"
                    compose.onNodeWithTag(tag).assertExists()
                    val result = layout(tag)
                    val rendered = result.layoutInput.text.text
                    check(rendered == entry.definition.substring(block.start, block.endExclusive)) { "Rendered block differs from dictionary source" }
                    assertTextFits(result)
                    observed.append(rendered)
                    previousEnd = block.endExclusive
                    blockReports.put(JSONObject().put("start", block.start).put("end", block.endExclusive)
                        .put("characters", rendered.length).put("sha256", hash(rendered))
                        .put("lines", result.lineCount).put("layoutWidth", result.size.width).put("layoutHeight", result.size.height))
                    assertFixedDetailActions()
                }
                check(previousEnd == entry.definition.length && observed.toString() == entry.definition)
                report.getJSONArray("entries").put(JSONObject().put("entryIndex", entryIndex)
                    .put("wordSha256", hash(entry.word)).put("definitionCharacters", entry.definition.length)
                    .put("definitionSha256", hash(entry.definition)).put("renderedJoinedSha256", hash(observed.toString()))
                    .put("firstBlockSha256", hash(expectedBlocks.first().text)).put("lastBlockSha256", hash(expectedBlocks.last().text))
                    .put("allBlocksMatchOriginal", true).put("allLayoutsFit", true).put("blocks", blockReports))
            }

            stage = "restore_detail_at_end"
            val restorationEntryIndex = expected.indices.maxBy { expected[it].definition.length }
            selectEntry(restorationEntryIndex, expected.size)
            val lastEntry = expected[restorationEntryIndex]
            val lastBlocks = DictionaryPresentation.blocks(lastEntry.definition)
            compose.onNodeWithTag("dictionary_detail_list").performScrollToIndex(lastBlocks.size)
            compose.onNodeWithTag("dictionary_detail_list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            compose.waitForIdle()
            assertFixedDetailActions()
            val beforeRecreate = position()
            check(beforeRecreate.firstBlock > 0 && beforeRecreate.lastBlock == lastBlocks.last().start)
            screenshot(output, report, "03-detail-at-end.png")
            activity.recreate()
            awaitDetail()
            compose.waitUntil(30_000) { runCatching { samePosition(beforeRecreate, position(), app.resources.displayMetrics.density) }.getOrDefault(false) }
            val restored = position()
            assertVisibleBlocks(lastEntry)
            check(runBlocking { repo.lookup("world") } == expected)
            if (expected.size > 1) check(stateDescription("dictionary_detail_choose_match") == "第 ${restorationEntryIndex + 1} 个，共 ${expected.size} 个匹配")
            check(!keyboardVisible())
            assertFixedDetailActions()
            report.put("positionBeforeRecreate", beforeRecreate.json()).put("positionAfterRecreate", restored.json())
                .put("restorationEntryIndex", restorationEntryIndex)
                .put("detailPositionRestored", true).put("sameLocalResultAfterRecreate", true)
            screenshot(output, report, "04-restored-detail.png")

            stage = "return_and_reopen_detail"
            compose.onNodeWithTag("dictionary_detail_back").performClick()
            awaitCapture()
            assertDraft()
            check(!keyboardVisible())
            assertOriginalCapture(repo, first)
            report.put("draftAndCursorsAfterBack", draftSignature()).put("backKeptDraftAndCursors", true)
            reach("capture_dictionary_toggle").performClick()
            awaitDetail()
            compose.waitUntil(15_000) { samePosition(restored, position(), app.resources.displayMetrics.density) }
            report.put("reopenedDetailKeptPosition", true)
            compose.onNodeWithTag("dictionary_detail_sentence").performClick()
            awaitCapture()
            compose.onNodeWithTag("capture_sentence").assertIsDisplayed()
            assertDraft()
            check(!keyboardVisible())
            assertOriginalCapture(repo, first)
            report.put("sentenceActionKeptDraftAndCursors", true).put("sentenceActionDidNotOpenIme", true)
            screenshot(output, report, "05-returned-to-sentence.png")

            stage = "close_detail_and_open_other_capture"
            reach("capture_dictionary_toggle").performClick()
            awaitDetail()
            compose.onNodeWithTag("dictionary_detail_close").assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { !exists("dictionary_detail") && !exists("capture_target") && exists("pending_captures") }
            assertOriginalCapture(repo, first)
            openCapture(second)
            check(!exists("dictionary_detail"))
            check(field("capture_target") == second.target && field("capture_sentence") == second.sentence)
            val secondEntries = runBlocking { repo.lookup(second.target) }
            compose.waitUntil(30_000) { !exists("capture_dictionary_lookup_loading") && !exists("capture_dictionary_lookup_error") }
            if (secondEntries.isEmpty()) {
                check(exists("capture_dictionary_empty") && !exists("capture_dictionary_definition_0"))
            } else {
                val secondPreview = layout("capture_dictionary_definition_0").layoutInput.text.text
                check(secondPreview.isNotBlank() && secondEntries.first().definition.startsWith(secondPreview))
            }
            report.put("otherCaptureKeptOwnDraft", true).put("oldDetailAbsentForOtherCapture", true)
                .put("otherCaptureResultIdentity", DictionaryPresentation.identity(dictionary.id, second.target, secondEntries))
            screenshot(output, report, "06-other-existing-capture.png")
            closeCapture()
            check(runBlocking { repo.dictionaryStatus() }.id == dictionary.id)
            requireOfflineNoKey(app, repo)
            report.put("dictionarySelectionUnchanged", true).put("offlineAndNoKeyAtEnd", true)
            succeeded = true
        } catch (error: Throwable) {
            report.put("failedStage", stage).put("failureType", error.javaClass.simpleName)
                .put("failureLocations", JSONArray(error.stackTrace.take(12).map { "${it.fileName}:${it.lineNumber}" }))
            runCatching { screenshot(output, report, "failure.png") }
        } finally {
            runCatching { scenario?.close() }.onFailure { succeeded = false; report.put("activityCloseFailed", true) }
            SystemClock.sleep(700)
            val snapshot = original
            if (snapshot != null) runCatching { restoreOwnedChanges(database, snapshot, report) }.onFailure { error ->
                succeeded = false
                report.put("preservationFailed", true).put("preservationFailureType", error.javaClass.simpleName)
                    .put("preservationFailureLocations", JSONArray(error.stackTrace.take(8).map { "${it.fileName}:${it.lineNumber}" }))
            }
            report.put("status", if (succeeded && snapshot != null) "passed" else "failed")
            reportFile.writeText(report.toString(2))
        }
        check(succeeded) { "Dictionary detail trial failed at $stage; inspect sanitized report" }
    }

    private fun openPending() {
        compose.onNodeWithText(">_  命令").performClick()
        compose.onNodeWithText("搜索命令…").performTextInput("inbox")
        compose.onNode(hasText("待补充捕获", substring = true)).performClick()
        compose.waitUntil(15_000) { exists("pending_captures") }
    }
    private fun openCapture(capture: Capture) {
        compose.onNodeWithTag("pending_captures").performScrollToNode(hasTestTag("pending_capture_${capture.id}"))
        compose.onNodeWithTag("pending_capture_${capture.id}").performClick()
        awaitCapture()
        check(field("capture_target") == capture.target)
    }
    private fun closeCapture() {
        if (keyboardVisible()) InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { !keyboardVisible() }
        compose.onNodeWithTag("capture_close").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { !exists("capture_target") && exists("pending_captures") }
    }
    private fun awaitCapture() { compose.waitUntil(20_000) { exists("capture_target") && !exists("dictionary_detail") } }
    private fun awaitDetail() {
        compose.waitUntil(30_000) { exists("dictionary_detail") && exists("dictionary_detail_list") &&
            !exists("dictionary_detail_loading") && !exists("dictionary_detail_preparing") && !exists("dictionary_detail_error") }
        compose.waitForIdle()
    }
    private fun selectEntry(index: Int, count: Int) {
        if (count > 1) {
            compose.onNodeWithTag("dictionary_detail_choose_match").performClick()
            reach("dictionary_detail_match_$index").performClick()
            awaitDetail()
        }
        compose.onNodeWithTag("dictionary_detail_list").performScrollToIndex(0)
        compose.onNodeWithTag("dictionary_detail_word").assertExists()
    }
    private fun assertFixedDetailActions() {
        listOf("dictionary_detail_header", "dictionary_detail_back", "dictionary_detail_close", "dictionary_detail_sentence")
            .forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
    }
    private fun setCursor(tag: String, position: Int) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetSelection) { it(position, position, false) }
    }
    private fun assertDraft() {
        check(field("capture_target") == "world" && field("capture_sentence") == DRAFT)
        check(selection("capture_target") == TextRange(TARGET_CURSOR) && selection("capture_sentence") == TextRange(SENTENCE_CURSOR))
    }
    private fun draftSignature() = JSONObject().put("targetSha256", hash(field("capture_target")))
        .put("sentenceSha256", hash(field("capture_sentence"))).put("targetCursor", selection("capture_target").start)
        .put("sentenceCursor", selection("capture_sentence").start)
    private fun assertOriginalCapture(repo: AppRepository, original: Capture) {
        check(runBlocking { repo.captures.first() }.single { it.id == original.id } == original) { "Unsaved draft changed the stored capture" }
    }
    private fun dictionaryReady(query: String) = !exists("capture_dictionary_lookup_loading") &&
        !exists("capture_dictionary_lookup_error") && text("capture_dictionary_word_0")?.trim()?.startsWith(query, true) == true &&
        text("capture_dictionary_definition_0")?.isNotEmpty() == true
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun text(tag: String): String? = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
        ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
    private fun field(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun selection(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
    private fun stateDescription(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    private fun reach(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag).also {
        if (runCatching { it.assertIsDisplayed() }.isFailure) it.performScrollTo()
    }
    private fun layout(tag: String): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }
    private fun assertTextFits(result: TextLayoutResult) {
        check(!result.hasVisualOverflow && !result.didOverflowWidth && !result.didOverflowHeight)
        check((0 until result.lineCount).none(result::isLineEllipsized))
        check((0 until result.lineCount).all { result.getLineLeft(it) >= -1f && result.getLineRight(it) <= result.size.width + 1f })
    }
    private data class DetailPosition(val firstBlock: Int, val firstTop: Float, val lastBlock: Int, val scrollValue: Float) {
        fun json() = JSONObject().put("firstVisibleBlockStart", firstBlock).put("firstBlockTopRelativePx", firstTop)
            .put("lastVisibleBlockStart", lastBlock).put("scrollValue", scrollValue)
    }
    private val blockMatcher = SemanticsMatcher("dictionary detail text block") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("dictionary_detail_block_") == true
    }
    private fun position(): DetailPosition {
        val list = compose.onNodeWithTag("dictionary_detail_list").fetchSemanticsNode()
        val viewport = list.boundsInRoot
        val visible = compose.onAllNodes(blockMatcher).fetchSemanticsNodes().filter {
            it.boundsInRoot.height > 0f && it.boundsInRoot.bottom > viewport.top && it.boundsInRoot.top < viewport.bottom
        }.sortedBy { it.config[SemanticsProperties.TestTag].substringAfterLast('_').toInt() }
        check(visible.isNotEmpty())
        val first = visible.first()
        return DetailPosition(first.config[SemanticsProperties.TestTag].substringAfterLast('_').toInt(),
            first.positionInRoot.y - viewport.top,
            visible.last().config[SemanticsProperties.TestTag].substringAfterLast('_').toInt(),
            list.config[SemanticsProperties.VerticalScrollAxisRange].value())
    }
    private fun samePosition(before: DetailPosition, after: DetailPosition, density: Float) =
        before.firstBlock == after.firstBlock && before.lastBlock == after.lastBlock && abs(before.firstTop - after.firstTop) <= 3f * density
    private fun assertVisibleBlocks(entry: DictionaryEntry) {
        val ranges = DictionaryPresentation.blocks(entry.definition).associateBy { it.start }
        compose.onAllNodes(blockMatcher).fetchSemanticsNodes().forEach { node ->
            val tag = node.config[SemanticsProperties.TestTag]
            val start = tag.substringAfterLast('_').toInt()
            check(layout(tag).layoutInput.text.text == ranges.getValue(start).text)
        }
    }
    private fun keyboardVisible(): Boolean {
        var visible = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            visible = WindowInspector.getGlobalWindowViews().any { it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        }
        return visible
    }
    private fun requireOfflineNoKey(app: LexiconApplication, repo: AppRepository) {
        check(app.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
        check(!runBlocking { repo.hasApiKey() })
    }

    private fun restoreOwnedChanges(database: File, original: Map<String, List<List<String?>>>, report: JSONObject) {
        val originalPreferences = original.getValue("preferences")
        val originalSettings = requireNotNull(originalPreferences.single { it[0] == "settings" }[1])
        val currentPreferences = readRows(database, "preferences")
        val originalKeys = originalPreferences.map { it[0] }.toSet()
        val trialDay = report.optString("trialEditionDay")
        val trialEditionKey = "reading-library:edition:$trialDay"
        val ownedEditions = currentPreferences.filter { row -> row[0] !in originalKeys && trialDay.isNotBlank() && row[0] == trialEditionKey }
        val articleIds = original.getValue("articles").map { it[0] }.toSet()
        ownedEditions.forEach { row ->
            val edition = codec.decodeFromString<DailyEdition>(requireNotNull(row[1]))
            check(row[0] == "reading-library:edition:${edition.day}" && edition.day == trialDay)
            check(edition.articleIds.all { it in articleIds })
        }
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                val actualSettings = requireNotNull(readRows(db, "preferences").single { it[0] == "settings" }[1])
                check(actualSettings == originalSettings || hash(actualSettings) == report.optString("trialSettingsHash")) { "Settings changed outside trial" }
                ownedEditions.forEach { row ->
                    check(readRows(db, "preferences").single { it[0] == row[0] } == row)
                    db.delete("preferences", "`key`=?", arrayOf(requireNotNull(row[0])))
                }
                db.compileStatement("UPDATE preferences SET data=? WHERE `key`='settings'").use {
                    it.bindString(1, originalSettings); it.executeUpdateDelete()
                }
                check(readSnapshot(db) == original) { "Original database rows changed" }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        val after = readSnapshot(database)
        check(after == original)
        report.put("originalDataPreserved", true).put("settingsRestored", true)
            .put("removedOnlyOwnedEditionRows", ownedEditions.size).put("finalTableHashes", snapshotHashes(after))
    }
    private fun readSnapshot(file: File): Map<String, List<List<String?>>> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use(::readSnapshot)
    private fun readSnapshot(db: SQLiteDatabase): Map<String, List<List<String?>>> {
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return tables.associateWith { readRows(db, it) }
    }
    private fun readRows(file: File, table: String): List<List<String?>> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { readRows(it, table) }
    private fun readRows(db: SQLiteDatabase, table: String): List<List<String?>> {
        require(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
        return db.rawQuery("SELECT * FROM \"$table\" ORDER BY 1", null).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
        }
    }
    private fun snapshotHashes(snapshot: Map<String, List<List<String?>>>) = JSONObject().apply {
        snapshot.forEach { (table, rows) -> put(table, JSONObject().put("rows", rows.size)
            .put("sha256", hash(JSONArray(rows.map { JSONArray(it) }).toString()))) }
    }
    private fun screenshot(output: File, report: JSONObject, name: String) {
        compose.waitForIdle()
        val file = File(output, name)
        check(!file.exists())
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
        report.getJSONArray("screenshots").put(name)
    }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private companion object {
        const val DRAFT = "A working draft keeps its cursor and does not create a saved capture."
        const val TARGET_CURSOR = 2
        const val SENTENCE_CURSOR = 16
    }
}
