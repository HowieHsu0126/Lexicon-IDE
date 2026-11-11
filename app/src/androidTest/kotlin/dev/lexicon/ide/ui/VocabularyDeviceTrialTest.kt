package dev.lexicon.ide.ui

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import dev.lexicon.ide.LexiconApplication
import dev.lexicon.ide.MainActivity
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit trial of installed publisher material. No fixture, automatic paid retry, or ability claim. */
@RunWith(AndroidJUnit4::class)
class VocabularyDeviceTrialTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val codec = Json { ignoreUnknownKeys = true }
    private data class Material(val article: Article, val body: ArticleBody, val study: ArticleStudy,
        val candidates: List<VocabularyCandidate>, val allowed: Boolean)
    private data class Original(
        val settings: AppSettings, val settingsBytes: ByteArray?, val profileBytes: ByteArray?, val pausedBytes: ByteArray?,
        val hasKey: Boolean, val cards: Map<String, Card>, val captures: Map<String, Capture>,
        val batches: Map<String, VocabularyBatch>, val jobs: Map<String, AiJob>, val sessions: Map<String, ByteArray>,
        val otherLearning: Map<String, ByteArray>, val reviews: Map<String, ByteArray>, val familiar: Map<String, ByteArray>,
    )

    @Test fun realVocabularyCandidatesRemainAvailableAndExplicitQueuePreservesTheirOriginalContext() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Vocabulary interaction trial requires explicit opt-in", args.getString("lexiconVocabularyTrial") == "1")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiconApplication
        val repository = app.repository
        val stagedKey = File(app.filesDir, "lexicon-trial-api-key")
        val output = File(app.filesDir, "validation-1.8.0/vocabulary-trial").apply { mkdirs() }
        val report = JSONObject().put("status", "started").put("automatedInteractionTrial", true)
            .put("humanReadingPerformanceMeasured", false).put("networkTrafficMeasured", false)
            .put("liveVocabularyRequested", args.getString("lexiconLiveVocabulary") == "1")
            .put("visibleGenerationConfirmations", 0).put("maximumSelectedItems", 2)
            .put("maximumInternalSchemaRepairsPerItem", 1)
            .put("operations", JSONArray()).put("screenshots", JSONArray()).put("untestedPaths", JSONArray())
        fun save() { File(output, "device-trial.json").writeText(report.toString(2)) }
        fun skip(reason: String): Nothing {
            report.put("status", "skipped").put("reason", reason); save()
            assumeTrue(reason, false); error("Skipped")
        }
        val database = app.getDatabasePath("lexicon.sqlite")
        var original: Original? = null
        var material: Material? = null
        var touchedFamiliarKey: String? = null
        var activity: ActivityScenario<MainActivity>? = null
        var temporaryKeyInstalled = false
        var preferencesChanged = false
        var failed = false
        var confirmedCaptureIds = emptySet<String>()
        val startedAt = System.currentTimeMillis()
        save()
        try {
            runBlocking { repository.initialize() }
            if (!database.isFile) skip("The installed database is unavailable")
            val vocabulary = repository.vocabulary ?: skip("Vocabulary repository is unavailable")
            val reading = repository.reading ?: skip("Reading repository is unavailable")
            val jobs = allJobs(database)
            val library = runBlocking { vocabulary.library.first() }
            if (jobs.values.any { it.status == "running" } || library.batches.any { b -> b.items.any { it.status == VocabularyItemStatus.RUNNING } })
                skip("Existing AI work is running; the trial will not overlap it")
            val chosen = materials(database, runBlocking { repository.articles.first() }, runBlocking { repository.sources.first() }, reading)
                .firstOrNull { it.candidates.size >= 2 } ?: skip("No eligible cached The Conversation study has two vocabulary candidates")
            material = chosen
            val cards = runBlocking { repository.cards.first() }.associateBy { it.id }
            val captures = runBlocking { repository.captures.first() }.associateBy { it.id }
            original = Original(runBlocking { repository.settings.first() }, preference(database, "settings"),
                preference(database, "personalization:profile"), preference(database, "ai_paused"), runBlocking { repository.hasApiKey() },
                cards, captures, library.batches.associateBy { it.id }, jobs, sessionRows(database), protectedLearningRows(database),
                tableRows(database, "review_logs"), familiarRows(database))
            val saved = requireNotNull(original)
            report.put("articleId", chosen.article.id).put("bodyHash", chosen.body.contentHash)
                .put("publisherHost", "theconversation.com").put("candidateCount", chosen.candidates.size)
                .put("priorCardCount", cards.size).put("priorBatchCount", library.batches.size)
            runBlocking { repository.updateSettings(saved.settings.copy(titleClassification = false,
                reader = saved.settings.reader.copy(automaticStudy = false))) }
            preferencesChanged = true
            if (args.getString("lexiconLiveVocabulary") == "1" && args.getString("lexiconPrivateApiFile") == "1") {
                if (saved.hasKey) skip("The staged key cannot replace an existing installed key")
                val key = stagedKey.inputStream().use(::readApiKeyInput)
                temporaryKeyInstalled = true
                runBlocking { repository.setApiKey(key) }
                check(runBlocking { repository.hasApiKey() })
                check(!stagedKey.exists() || stagedKey.delete())
                report.put("temporaryEncryptedKeyImported", true)
            }
            activity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { exists("daily_reading") }
            openArticle(chosen)
            openTools("重点")
            showAllCandidates(chosen)
            screenshot(output, report, "01-original-vocabulary.png")

            val first = chosen.candidates.firstOrNull { !VocabularyLearning.isFamiliar(it.capture.target, library.familiar) }
                ?: chosen.candidates.first()
            touchedFamiliarKey = FAMILIAR_PREFIX + fingerprint(first.termKey)
            val originallyFamiliar = VocabularyLearning.isFamiliar(first.capture.target, library.familiar)
            node("vocabulary_familiar_${first.id}").performClick()
            waitFamiliar(vocabulary, first, !originallyFamiliar)
            showAllCandidates(chosen)
            node("vocabulary_familiar_${first.id}").performClick()
            waitFamiliar(vocabulary, first, originallyFamiliar)
            report.put("familiarityToggledAndUndone", true)
            showAllCandidates(chosen)
            node("vocabulary_context_${first.id}").performClick()
            assertNodeText("vocabulary_sentence_${first.id}", first.capture.sentence)
            report.put("exactOriginalSentencePreview", true)
            screenshot(output, report, "02-original-sentence-and-familiarity.png")
            step(output, report, "Toggled and restored one explicit familiarity decision; expanded its unchanged original sentence")

            node("reader_tab_略读").performClick()
            compose.onNodeWithText("开始略读").reachReadingTool().performClick()
            compose.waitUntil(10_000) { !exists("reader_tab_重点") }
            openTools("重点"); showAllCandidates(chosen)
            report.put("candidatesPreservedInSkim", true)
            node("reader_quiet").performClick()
            compose.waitUntil(10_000) { !exists("reader_tab_重点") }
            openTools("重点"); showAllCandidates(chosen)
            report.put("candidatesPreservedInQuiet", true)
            screenshot(output, report, "03-quiet-skim-vocabulary.png")
            node("reader_quiet").performClick()
            compose.waitUntil(10_000) { !exists("reader_tab_重点") }
            openTools("重点"); showAllCandidates(chosen)
            step(output, report, "Confirmed original learning candidates remain available in skim and quiet reading")

            val currentLibrary = runBlocking { vocabulary.library.first() }
            val priorQueuedIds = saved.batches.values.flatMap { b -> b.items.map { it.candidate.id } }.toSet()
            val available = chosen.candidates.filter { c ->
                val matchingCaptureIds = captures.values.filter { VocabularyLearning.sameCapture(it, c.capture) }.map { it.id }.toSet() + c.capture.id
                !VocabularyLearning.isFamiliar(c.capture.target, currentLibrary.familiar) &&
                    c.id !in priorQueuedIds && existingCard(c, cards.values, captures.values) == null &&
                    saved.jobs.values.none { it.entityId in matchingCaptureIds }
            }.take(2)
            if (available.size == 2) {
                available.forEach { node("vocabulary_select_${it.id}").performClick() }
                val beforeCancel = runBlocking { vocabulary.library.first() }
                val cancelJobs = runBlocking { repository.jobs.first() }
                node("vocabulary_prepare").performClick()
                compose.waitUntil(10_000) { exists("vocabulary_confirmation") }
                val dialog = compose.onNodeWithTag("vocabulary_confirmation").fetchSemanticsNode()
                // Only compare text in memory; never print a failed assertion containing learning material.
                val dialogTexts = texts(dialog)
                assertTrue("The confirmation must include both frozen original sentences",
                    available.all { candidate -> dialogTexts.any { it.contains(candidate.capture.sentence) } })
                screenshot(output, report, "04-two-original-sentences-confirmation.png")
                compose.onNodeWithTag("vocabulary_cancel").performClick()
                compose.waitUntil(10_000) { !exists("vocabulary_confirmation") }
                assertTrue("Cancel cannot persist a vocabulary batch", beforeCancel == runBlocking { vocabulary.library.first() })
                assertTrue("Cancel cannot start an AI task", cancelJobs == runBlocking { repository.jobs.first() })
                assertTrue("Cancel cannot create captures", saved.captures == runBlocking { repository.captures.first() }.associateBy { it.id })
                report.put("confirmationCancelHasNoQueueOrApiJob", true)
                step(output, report, "Selected exactly two words, checked their original sentences, then cancelled without saving a batch or starting AI")
                if (args.getString("lexiconLiveVocabulary") == "1") {
                    if (!chosen.allowed || !runBlocking { repository.hasApiKey() }) {
                        report.put("liveStatus", "unavailable").put("status", "partial")
                        report.getJSONArray("untestedPaths").put("No source permission or configured key; no confirmation was invoked")
                    } else {
                        confirmedCaptureIds = available.flatMap { candidate ->
                            captures.values.filter { VocabularyLearning.sameCapture(it, candidate.capture) }.map { it.id } + candidate.capture.id
                        }.toSet()
                        runLiveQueue(output, report, repository, vocabulary, chosen, available)
                    }
                } else report.put("liveStatus", "not_requested")
            } else {
                val reusable = chosen.candidates.filter { existingCard(it, cards.values, captures.values) != null }.take(2)
                report.put("existingReusableCardCount", reusable.size)
                if (reusable.size == 2) {
                    reusable.forEach { assertExactCard(chosen, it, requireNotNull(existingCard(it, cards.values, captures.values))) }
                    report.put("liveStatus", "existing_cards_reused")
                } else report.put("liveStatus", "insufficient_unqueued_candidates").put("status", "partial")
                report.getJSONArray("untestedPaths").put("Two fresh selectable candidates were unavailable; no prior queue was resumed and no forced regeneration occurred")
            }
            val finalCards = runBlocking { repository.cards.first() }.associateBy { it.id }
            val finalCaptures = runBlocking { repository.captures.first() }.associateBy { it.id }
            val finalLibrary = runBlocking { vocabulary.library.first() }
            assertTrue("Prior cards must remain unchanged", saved.cards.all { (id, card) -> finalCards[id] == card })
            assertTrue("Prior captures must remain unchanged", saved.captures.all { (id, capture) -> finalCaptures[id] == capture })
            assertTrue("Prior vocabulary batches must remain unchanged", saved.batches.all { (id, batch) -> finalLibrary.batches.find { it.id == id } == batch })
            assertTrue("Other learning records must remain unchanged", sameRows(saved.otherLearning, protectedLearningRows(database)))
            assertTrue("Review histories must remain unchanged", sameRows(saved.reviews, tableRows(database, "review_logs")))
            val finalJobs = allJobs(database)
            assertTrue("Prior AI jobs must remain unchanged", saved.jobs.all { (id, job) -> finalJobs[id] == job })
            val addedJobs = finalJobs.values.filter { it.id !in saved.jobs }
            val newCaptureIds = finalCaptures.keys - saved.captures.keys
            val newCards = finalCards.values.filter { it.id !in saved.cards }
            assertTrue("Only confirmed candidates may add captures", newCaptureIds.size <= 2 && newCaptureIds.all { it in confirmedCaptureIds })
            assertTrue("Only confirmed candidates may add cards", newCards.size <= 2 && newCards.all { it.captureId in confirmedCaptureIds })
            assertTrue("A single confirmation may add at most one batch", finalLibrary.batches.count { it.id !in saved.batches } <= report.getInt("visibleGenerationConfirmations"))
            assertTrue("Only the two selected vocabulary items may start AI jobs", addedJobs.size <= 2 &&
                addedJobs.all { it.kind == "vocabulary" && it.entityId in confirmedCaptureIds })
            if (report.getInt("visibleGenerationConfirmations") == 0) {
                assertTrue("Browsing and cancelling cannot add AI jobs", addedJobs.isEmpty())
                assertTrue("Browsing and cancelling cannot add cards", finalCards == saved.cards)
            }
            report.put("newAiJobCount", addedJobs.size).put("newCardCount", finalCards.size - saved.cards.size)
                .put("newCaptureCount", finalCaptures.size - saved.captures.size).put("priorLearningRecordsPreserved", true)
            if (report.getString("status") == "started") report.put("status", "passed")
            report.getJSONArray("untestedPaths").put("Physical MagicOS frame performance").put("Long-term learning efficacy")
            save()
        } catch (skip: AssumptionViolatedException) { throw skip }
        catch (failure: Throwable) {
            failed = true
            report.put("status", "failed").put("failureType", failure.javaClass.simpleName)
                .put("failureLocations", JSONArray(failure.stackTrace.take(12).map { "${it.fileName}:${it.lineNumber}" }))
            runCatching { screenshot(output, report, "failure.png") }; save()
            // Compose assertion exceptions can contain the full semantics tree. Do not print it.
            throw AssertionError("Vocabulary device trial failed; inspect its sanitized report and screenshot")
        } finally {
            val close = runCatching { activity?.close() }
            val key = runCatching {
                if (temporaryKeyInstalled) runBlocking { repository.clearApiKey() }
                check(!stagedKey.exists() || stagedKey.delete())
                original?.let { saved ->
                    if (temporaryKeyInstalled) restorePreference(database, "ai_paused", saved.pausedBytes)
                    check(runBlocking { repository.hasApiKey() } == saved.hasKey)
                }
            }
            val restored = runCatching {
                original?.let { saved ->
                    material?.let { chosen ->
                        awaitSessionWrites(database)
                        cleanupOwnSessions(database, saved.sessions, chosen.article.id, startedAt, System.currentTimeMillis(), report)
                    }
                    touchedFamiliarKey?.let { key -> restorePreference(database, key, saved.familiar[key]) }
                    check(sameRows(saved.familiar, familiarRows(database)))
                    if (preferencesChanged) {
                        try { runBlocking {
                            repository.updateSettings(saved.settings)
                            withTimeout(10_000) { repository.settings.first { it == saved.settings } }
                        } } finally { restorePreference(database, "settings", saved.settingsBytes) }
                        restorePreference(database, "personalization:profile", saved.profileBytes)
                        check(sameBytes(saved.settingsBytes, preference(database, "settings")))
                        check(sameBytes(saved.profileBytes, preference(database, "personalization:profile")))
                    }
                }
            }
            report.put("activityClosed", close.isSuccess).put("apiKeyAndStagingRestored", key.isSuccess)
                .put("preferencesFamiliarityAndSessionsRestored", restored.isSuccess)
            if (close.isFailure || key.isFailure || restored.isFailure) report.put("status", "failed").put("restorationFailure", true)
            save()
            if (!failed && (close.isFailure || key.isFailure || restored.isFailure))
                throw AssertionError("Vocabulary trial cleanup did not complete; inspect its sanitized report")
        }
    }

    private fun runLiveQueue(output: File, report: JSONObject, repository: AppRepository, vocabulary: VocabularyLearningRepository,
        material: Material, selected: List<VocabularyCandidate>) {
        val batchId = VocabularyLearning.batchId(selected)
        node("vocabulary_prepare").performClick()
        compose.waitUntil(10_000) { exists("vocabulary_confirmation") }
        compose.onNodeWithTag("vocabulary_confirm").performClick()
        report.put("visibleGenerationConfirmations", 1).put("liveStatus", "waiting")
        step(output, report, "Confirmed one batch of exactly two original vocabulary candidates; no explicit retry will be issued")
        compose.waitUntil(15_000) { !exists("vocabulary_confirmation") }
        pressBack()
        compose.waitUntil(10_000) { !exists("reader_tab_重点") && exists("zen_article_text") }
        assertTrue("The original remains readable while the batch runs", editorText() == material.body.text)
        screenshot(output, report, "05-reading-after-confirmation.png")
        report.put("readerUsableAfterQueueConfirmation", true)
        pressBack()
        compose.waitUntil(10_000) { exists("article_history_list") }
        openCommand("vocabulary", "词汇队列")
        compose.waitUntil(10_000) { exists("vocabulary_queue_screen") }
        var result: VocabularyBatch? = null
        var nextRead = 0L
        val wait = runCatching { compose.waitUntil(300_000) {
            val now = SystemClock.elapsedRealtime()
            if (now < nextRead) false else {
                nextRead = now + 500
                val batch = runBlocking { vocabulary.library.first() }.batches.find { it.id == batchId }
                result = batch
                batch != null && (batch.items.all { it.status == VocabularyItemStatus.COMPLETED } ||
                    batch.items.any { it.status in setOf(VocabularyItemStatus.FAILED, VocabularyItemStatus.INTERRUPTED) })
            }
        } }
        val batch = result
        if (wait.isFailure || batch == null || batch.items.any { it.status != VocabularyItemStatus.COMPLETED }) {
            report.put("status", "partial").put("liveStatus", if (wait.isFailure) "timeout" else "failed")
            report.put("itemStatuses", JSONArray(batch?.items.orEmpty().map { it.status.name }))
            report.getJSONArray("untestedPaths").put("The single explicit batch did not finish both cards; no retry was issued")
            screenshot(output, report, "06-queue-partial.png")
            return
        }
        assertTrue("The frozen batch must contain only the two confirmed candidates", batch.items.map { it.candidate.id }.toSet() == selected.map { it.id }.toSet())
        val cards = runBlocking { repository.cards.first() }.associateBy { it.id }
        batch.items.forEach { item -> assertExactCard(material, item.candidate, requireNotNull(cards[item.cardId])) }
        node("vocabulary_batch_$batchId")
        screenshot(output, report, "06-two-completed-cards.png")
        val beforeReopen = runBlocking { repository.jobs.first() }
        val batchesBeforeReopen = runBlocking { vocabulary.library.first() }
        openCommand("explorer", "打开阅读列表")
        compose.waitUntil(10_000) { exists("daily_reading") }
        openArticle(material); openTools("重点"); showAllCandidates(material)
        selected.forEach { node("vocabulary_saved_${it.id}") }
        assertTrue("Reopening does not start another model task", beforeReopen == runBlocking { repository.jobs.first() })
        assertTrue("Reopening does not change the completed queue", batchesBeforeReopen == runBlocking { vocabulary.library.first() })
        report.put("liveStatus", "succeeded").put("completedItems", 2).put("exactSourceCardsVerified", true)
            .put("reopeningHasNoNewJobs", true)
        screenshot(output, report, "07-reopened-saved-vocabulary.png")
        step(output, report, "Observed two completed cards in the global queue and reopened the original without creating another AI task")
    }

    private fun existingCard(candidate: VocabularyCandidate, cards: Collection<Card>, captures: Collection<Capture>): Card? {
        val ids = captures.filter { VocabularyLearning.sameCapture(it, candidate.capture) }.map { it.id }.toSet() + candidate.capture.id
        return cards.firstOrNull { it.captureId in ids }
    }
    private fun assertExactCard(material: Material, candidate: VocabularyCandidate, card: Card) {
        val capture = candidate.capture
        val from = requireNotNull(capture.selectionStart); val end = requireNotNull(capture.selectionEnd)
        assertTrue("The candidate must retain its exact original range", capture.bodyHash == material.body.contentHash &&
            from >= 0 && end <= material.body.text.length && material.body.text.substring(from, end) == capture.selectedText)
        assertTrue("The saved card must preserve the frozen original sentence and source", card.sourceSentence == capture.sentence &&
            card.sourceRef == capture.sourceRef && card.contextOrigin == ContextOrigin.ORIGINAL)
        assertTrue("The saved card must have valid analysis fields", ContentValidator.validate(card.content).isEmpty())
    }
    private fun waitFamiliar(repository: VocabularyLearningRepository, candidate: VocabularyCandidate, expected: Boolean) {
        compose.waitUntil(10_000) { VocabularyLearning.isFamiliar(candidate.capture.target, runBlocking { repository.library.first() }.familiar) == expected }
    }
    private fun showAllCandidates(material: Material) {
        compose.waitUntil(10_000) { exists("vocabulary_show_familiar") || material.candidates.any { exists("vocabulary_candidate_${it.id}") } }
        if (exists("vocabulary_show_familiar")) {
            val control = node("vocabulary_show_familiar")
            if (runCatching { control.fetchSemanticsNode().config[SemanticsProperties.Selected] }.getOrDefault(false).not()) control.performClick()
        }
        material.candidates.forEach {
            assertTrue("Every original vocabulary candidate remains available", exists("vocabulary_candidate_${it.id}"))
        }
    }
    private fun openArticle(material: Material) {
        compose.onNodeWithTag("daily_reading").performScrollToNode(hasText("读过的文章"))
        compose.onNodeWithText("读过的文章").performClick()
        compose.waitUntil(10_000) { exists("article_history_list") }
        compose.onNodeWithTag("article_history_list").performScrollToNode(hasText(material.article.title))
        compose.onNodeWithText(material.article.title).performClick()
        compose.waitUntil(15_000) { exists("zen_article_text") }
        assertTrue("The reader must use the already cached original", editorText() == material.body.text)
    }
    private fun openTools(tab: String) {
        val label = if (compose.onAllNodesWithText("略读 · 工具").fetchSemanticsNodes().isNotEmpty()) "略读 · 工具" else "精读 · 工具"
        compose.onNodeWithText(label).performScrollTo().performClick()
        node("reader_tab_$tab").performClick()
        compose.waitUntil(10_000) { !exists("vocabulary_loading") }
    }
    private fun openCommand(query: String, label: String) {
        compose.onNodeWithText(">_  命令").performClick()
        compose.onNode(hasSetTextAction()).performTextInput(query)
        compose.onNodeWithText(label).performClick()
    }
    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag).reachReadingTool()
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun pressBack() = InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    private fun editorText() = compose.onNodeWithTag("zen_article_text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun assertNodeText(tag: String, expected: String) {
        val values = runCatching { node(tag).fetchSemanticsNode().config[SemanticsProperties.Text] }.getOrDefault(emptyList()).map { it.text }
        assertTrue("The displayed original sentence must match the frozen candidate", expected in values)
    }
    private fun texts(node: androidx.compose.ui.semantics.SemanticsNode): List<String> =
        runCatching { node.config[SemanticsProperties.Text] }.getOrDefault(emptyList()).map { it.text } + node.children.flatMap(::texts)
    private fun step(output: File, report: JSONObject, description: String) {
        report.getJSONArray("operations").put(description)
        File(output, "device-trial.json").writeText(report.toString(2))
    }
    private fun screenshot(output: File, report: JSONObject, name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync(); SystemClock.sleep(150)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle(); report.getJSONArray("screenshots").put(name)
        File(output, "device-trial.json").writeText(report.toString(2))
    }
    private fun materials(database: File, articles: List<Article>, sources: List<Source>, reading: ReadingRepository): List<Material> {
        val exclude = Regex("(?i)fixture|synthetic|self-authored|trial|example\\.(org|com)|test-source|测试")
        return SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db -> articles.mapNotNull { article ->
            val uri = Uri.parse(article.url)
            if (!article.read || article.readingMode != ReadingMode.NATIVE || uri.scheme != "https" || uri.host != "theconversation.com" ||
                exclude.containsMatchIn(article.id + " " + article.title + " " + article.sourceName)) return@mapNotNull null
            val body = db.rawQuery("SELECT data FROM article_bodies WHERE articleId=? AND expiresAt>?",
                arrayOf(article.id, (System.currentTimeMillis() + 600_000).toString())).use { cursor ->
                if (cursor.moveToFirst()) runCatching { codec.decodeFromString<ArticleBody>(cursor.getString(0)) }.getOrNull() else null
            } ?: return@mapNotNull null
            if (body.articleId != article.id || body.text.length !in 400..24_000 || body.contentHash != fingerprint(body.text)) return@mapNotNull null
            val study = runBlocking { reading.cachedStudy(article.id, body.contentHash) } ?: return@mapNotNull null
            val allowed = sources.any { it.id in article.sourceIds && it.aiAllowed }
            val candidates = runCatching { VocabularyLearning.candidates(article, body, study, allowed) }.getOrNull() ?: return@mapNotNull null
            Material(article, body, study, candidates, allowed)
        } }
    }
    private fun preference(database: File, key: String): ByteArray? =
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT CAST(data AS BLOB) FROM preferences WHERE `key`=?", arrayOf(key)).use { if (it.moveToFirst()) it.getBlob(0) else null }
        }
    private fun restorePreference(database: File, key: String, bytes: ByteArray?) {
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            if (bytes == null) db.compileStatement("DELETE FROM preferences WHERE `key`=?").use { it.bindString(1, key); it.executeUpdateDelete() }
            else db.compileStatement("INSERT OR REPLACE INTO preferences (`key`,data) VALUES (?,CAST(? AS TEXT))").use {
                it.bindString(1, key); it.bindBlob(2, bytes); check(it.executeInsert() != -1L)
            }
        }
    }
    private fun preferenceRows(database: File, sql: String): Map<String, ByteArray> =
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql, emptyArray<String>()).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getBlob(1)) } }
        }
    private fun familiarRows(database: File) = preferenceRows(database, "SELECT `key`,CAST(data AS BLOB) FROM preferences WHERE `key` LIKE '$FAMILIAR_PREFIX%'")
    private fun sessionRows(database: File) = preferenceRows(database, "SELECT `key`,CAST(data AS BLOB) FROM preferences WHERE `key` LIKE 'reading-library:session:%'")
    private fun protectedLearningRows(database: File) = preferenceRows(database,
        "SELECT `key`,CAST(data AS BLOB) FROM preferences WHERE (`key` LIKE 'reading-library:%' AND `key` NOT LIKE 'reading-library:session:%' AND `key` NOT LIKE 'reading-library:edition:%') OR `key` LIKE 'speed-%' OR `key` LIKE 'baseline-%'")
    private fun tableRows(database: File, table: String) = preferenceRows(database, "SELECT id,CAST(data AS BLOB) FROM $table")
    private fun allJobs(database: File): Map<String, AiJob> = tableRows(database, "ai_jobs").mapValues { (_, bytes) -> codec.decodeFromString<AiJob>(bytes.toString(Charsets.UTF_8)) }
    private fun sameBytes(a: ByteArray?, b: ByteArray?) = if (a == null) b == null else b != null && a.contentEquals(b)
    private fun sameRows(a: Map<String, ByteArray>, b: Map<String, ByteArray>) = a.keys == b.keys && a.all { (k, v) -> sameBytes(v, b[k]) }
    private fun awaitSessionWrites(database: File) = runBlocking {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var prior = sessionRows(database); var stable = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(150); val next = sessionRows(database)
            if (!sameRows(prior, next)) { prior = next; stable = SystemClock.elapsedRealtime() }
            if (SystemClock.elapsedRealtime() - stable >= 900) return@runBlocking
        }
        error("Reading writes did not settle")
    }
    private fun cleanupOwnSessions(database: File, before: Map<String, ByteArray>, articleId: String, start: Long, end: Long, report: JSONObject) {
        val after = sessionRows(database)
        check(before.all { (key, bytes) -> sameBytes(bytes, after[key]) })
        val added = after.filterKeys { it !in before }
        val owned = added.map { (key, bytes) ->
            val session = codec.decodeFromString<ReadingSession>(bytes.toString(Charsets.UTF_8))
            check(key == "reading-library:session:${session.id}" && session.articleId == articleId &&
                session.startedAt in start..end && session.finishedAt <= end + 1_000 && !session.completed)
            key to session.id
        }
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                owned.forEach { (key, id) ->
                    val actual = db.rawQuery("SELECT CAST(data AS BLOB) FROM preferences WHERE `key`=?", arrayOf(key)).use { if (it.moveToFirst()) it.getBlob(0) else null }
                    check(sameBytes(added[key], actual)); check(db.delete("preferences", "`key`=?", arrayOf(key)) == 1)
                    db.delete("local_events", "id=?", arrayOf("reading-session:$id"))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        check(sameRows(before, sessionRows(database)))
        report.put("removedTrialSessionCount", owned.size).put("priorReadingSessionsExactlyPreserved", true)
    }
    companion object { private const val FAMILIAR_PREFIX = "vocabulary-library:familiar:v1:" }
}
