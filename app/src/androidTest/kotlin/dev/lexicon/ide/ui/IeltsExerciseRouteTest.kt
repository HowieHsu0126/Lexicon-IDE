package dev.lexicon.ide.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native Route + real ViewModel, with synthetic in-memory repositories and no HTTP/model or production writes. */
@RunWith(AndroidJUnit4::class)
class IeltsExerciseRouteTest {
    @get:Rule val compose = createComposeRule()
    private val stores = mutableListOf<ViewModelStore>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val screenshotRun = System.currentTimeMillis().toString()
    private val first = "The council approved solar panels for a public library."
    private val second = "The writer supports investment in clean energy while residents compare local priorities."
    private val original = "$first\n\n$second"
    private val article = Article("route-article", "route-guid", listOf("synthetic-source"), "A public library chooses clean energy",
        "https://example.invalid/route-article", "Synthetic article", readingMode = ReadingMode.NATIVE, sourceName = "Synthetic source")
    private val body = ArticleBody(article.id, original, IeltsGatewayProtocol.bodyHash(original), 1, 500, "Self-authored fixture")
    private val health = IeltsGatewayHealth("lexicon.ielts.health.v1", "ok", IeltsGatewayCapabilities("synthetic-1", "synthetic-corpus",
        "a".repeat(64), listOf("multiple_choice", "identifying_information", "identifying_writers_views", "short_text_completion"),
        listOf("extent_of_agreement"), "official_public_samples_and_format_metadata"))
    private val importedPreview = IeltsPairingPreview("https://study-computer.example/", "a7".repeat(32), 1_893_456_000_000)
    private val fileSecret = "PRIVATE_FILE_PAIRING_TOKEN_136"
    private val pairingDocument get() = "{\"token\":\"$fileSecret\",\"fixture\":true}".encodeToByteArray()

    @After fun dispose() {
        gates.forEach { it.complete(Unit) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { stores.forEach { it.clear() } }
    }

    private fun payload(id: String = "route-request") = IeltsGatewaySubmit(requestId = id, articleId = article.id, body = original,
        paragraphs = listOf(IeltsParagraph("p1", 0, first.length, first),
            IeltsParagraph("p2", first.length + 2, original.length, second)),
        readingRequests = listOf(IeltsChoiceRequest("q1"), IeltsInformationRequest("q2"), IeltsViewRequest("q3"), IeltsCompletionRequest("q4", 3, false)),
        writingTask2Request = IeltsWritingRequest("w2", IeltsWritingType.EXTENT_OF_AGREEMENT))

    private fun material(payload: IeltsGatewaySubmit): Pair<IeltsExerciseRequest, IeltsExercisePackage> {
        val request = IeltsExerciseRequest(IeltsContract.REQUEST, payload.requestId, payload.articleId,
            IeltsGatewayProtocol.bodyHash(payload.body), payload.paragraphs, "b".repeat(64),
            listOf(IeltsStyleSource("synthetic-format", "c".repeat(64), "Synthetic format metadata for UI tests.", IeltsStyleScope.PUBLIC_SAMPLE_FORMAT)),
            payload.readingRequests, payload.writingTask2Request)
        val firstPart = payload.paragraphs.first()
        val secondPart = payload.paragraphs.last()
        val evidence = IeltsEvidence(firstPart.paragraphId, firstPart.start, firstPart.end, firstPart.text)
        val writingEvidence = IeltsEvidence(secondPart.paragraphId, secondPart.start, secondPart.end, secondPart.text)
        val completion = payload.readingRequests.filterIsInstance<IeltsCompletionRequest>().single()
        val term = if (completion.maxWords == 1) "panels" else "solar panels"
        val termStart = firstPart.start + firstPart.text.indexOf(term)
        val completionPrompt = if (completion.maxWords == 1) "The council approved solar ____." else "The council approved ____."
        val exercise = IeltsExercisePackage(IeltsContract.PACKAGE, request.requestId, request.articleId, request.bodyHash,
            IeltsExerciseJson.requestHash(request), IeltsContract.LABEL, listOf("synthetic-format"), listOf(
                IeltsChoiceQuestion("q1", "What did the council approve?", listOf(
                    IeltsOption(IeltsChoice.A, "Solar panels"), IeltsOption(IeltsChoice.B, "A railway"),
                    IeltsOption(IeltsChoice.C, "A sports stadium"), IeltsOption(IeltsChoice.D, "A hospital")),
                    1, IeltsChoice.A, "PRIVATE_ROUTE_REFERENCE: The original paragraph names solar panels.", listOf(evidence)),
                IeltsInformationQuestion("q2", "The council approved equipment for a public library.", IeltsInformationAnswer.TRUE,
                    "PRIVATE_ROUTE_REFERENCE: The library is explicit in the original paragraph.", listOf(evidence)),
                IeltsViewQuestion("q3", "The writer wants the annual budget doubled.", IeltsViewAnswer.NOT_GIVEN,
                    "PRIVATE_ROUTE_REFERENCE: No amount is given.", emptyList(), payload.paragraphs.map { it.paragraphId },
                    "Neither supplied paragraph specifies a future budget amount."),
                IeltsCompletionQuestion("q4", completionPrompt, completion.maxWords, completion.allowNumbers,
                    IeltsContract.WORD_RULE, term, "PRIVATE_ROUTE_REFERENCE: This is an exact original phrase.",
                    listOf(IeltsEvidence(firstPart.paragraphId, termStart, termStart + term.length, term)))),
            IeltsWritingTask2("w2", IeltsWritingType.EXTENT_OF_AGREEMENT,
                "Public funds should support clean energy. To what extent do you agree or disagree?", 250, 40, listOf(writingEvidence)),
            IeltsUnavailableTask1("unavailable", "no_verified_visual"))
        IeltsExerciseValidator.validatePackage(request, exercise)
        return request to exercise
    }

    private inner class Practices : IeltsPracticeRepository {
        val history = MutableStateFlow<List<IeltsPracticeHistoryItem>>(emptyList())
        val values = ConcurrentHashMap<String, IeltsSavedPractice>()
        private val cached = ConcurrentHashMap<String, Pair<IeltsExerciseRequest, IeltsExercisePackage>>()
        private val mutex = Mutex()
        val saves = AtomicInteger()
        @Volatile var saveGate: CompletableDeferred<Unit>? = null
        @Volatile var failSave = false
        override suspend fun cache(request: IeltsExerciseRequest, exercise: IeltsExercisePackage): String {
            val hash = IeltsExerciseValidator.validatePackage(request, exercise).packageSha256
            cached.putIfAbsent(hash, request to exercise)
            return hash
        }
        override suspend fun begin(packageHash: String, attemptId: String, now: Long): IeltsSavedPractice {
            val input = requireNotNull(cached[packageHash])
            return values.getOrPut(attemptId) { IeltsSavedPractice(attemptId, packageHash, input.first, input.second,
                emptyList(), "", null, 1, now, now) }.also { publish() }
        }
        fun seed(id: String = "attempt-history", payload: IeltsGatewaySubmit = this@IeltsExerciseRouteTest.payload()): IeltsSavedPractice {
            val input = material(payload); val hash = IeltsExerciseJson.packageHash(input.second)
            cached[hash] = input
            return IeltsSavedPractice(id, hash, input.first, input.second, emptyList(), "", null, 1, 100, 100)
                .also { values[id] = it; publish() }
        }
        override suspend fun get(attemptId: String) = values[attemptId]
        override fun observeHistory() = history
        override suspend fun saveDraft(id: String, expectedRevision: Long, responses: List<IeltsResponse>, writingText: String, now: Long): IeltsSavedPractice = mutex.withLock {
            saves.incrementAndGet()
            val gate = saveGate.also { saveGate = null }; gate?.await()
            if (failSave) error("RAW_PRIVATE_FAILURE_MUST_NOT_RENDER")
            val current = values.getValue(id)
            check(current.revision == expectedRevision && !current.submitted)
            current.copy(draftResponses = responses.toList(), writingText = writingText, revision = current.revision + 1, updatedAt = now)
                .also { values[id] = it; publish() }
        }
        override suspend fun submit(id: String, expectedRevision: Long, now: Long): IeltsSavedPractice = mutex.withLock {
            val current = values.getValue(id)
            if (current.submitted) return@withLock current
            check(current.revision == expectedRevision)
            var state = IeltsPracticeSession.begin(current.request, current.exercise, current.attemptId)
            current.draftResponses.forEach { state = state.answer(it.questionId, it.answer) }
            val submission = state.write(current.writingText).prepareSubmission(now).pendingSubmission()
            current.copy(submission = submission, revision = current.revision + 1, updatedAt = now)
                .also { values[id] = it; publish() }
        }
        private fun publish() {
            history.value = values.values.sortedBy { it.attemptId }.map {
                IeltsPracticeHistoryItem(it.attemptId, it.attemptId, it.packageHash, it.request.articleId,
                    if (it.submitted) IeltsPracticeHistoryStatus.SUBMITTED else IeltsPracticeHistoryStatus.DRAFT,
                    it.exercise.readingQuestions.size, it.draftResponses.size, it.createdAt, it.updatedAt, it.submission?.submittedAt)
            }
        }
    }

    private inner class Generations(private val practices: Practices, paired: Boolean) : IeltsGenerationRepository {
        val history = MutableStateFlow<List<IeltsGenerationHistoryItem>>(emptyList())
        val records = ConcurrentHashMap<String, IeltsGenerationRecord>()
        val sent = CopyOnWriteArrayList<IeltsGatewaySubmit>()
        val pairs = AtomicInteger(); val refreshes = AtomicInteger()
        val inspections = AtomicInteger(); val importedPairs = AtomicInteger()
        @Volatile var pairedEndpoint = if (paired) "https://paired.example/" else null
        @Volatile var generationGate: CompletableDeferred<Unit>? = null
        @Volatile var pairGate: CompletableDeferred<Unit>? = null
        @Volatile var importedPairGate: CompletableDeferred<Unit>? = null
        @Volatile var failPair = false
        @Volatile var failImportedPair = false
        @Volatile var returnPrepared = false
        override suspend fun connection() = IeltsGatewayConnection(pairedEndpoint, pairedEndpoint != null, health.takeIf { pairedEndpoint != null })
        override suspend fun pair(endpoint: String, token: String): IeltsGatewayConnection {
            pairs.incrementAndGet(); pairGate?.await()
            if (failPair) error("RAW_PRIVATE_FAILURE_MUST_NOT_RENDER")
            require(token.isNotBlank()); pairedEndpoint = IeltsGatewayEndpoint.parse(endpoint).url
            return connection()
        }
        override suspend fun inspectPairing(document: ByteArray): IeltsPairingPreview {
            inspections.incrementAndGet()
            require(document.isNotEmpty())
            return importedPreview
        }
        override suspend fun pairImported(document: ByteArray): IeltsGatewayConnection {
            importedPairs.incrementAndGet(); importedPairGate?.await()
            if (failImportedPair) error("$fileSecret RAW_PRIVATE_FAILURE_MUST_NOT_RENDER")
            require(document.isNotEmpty()); pairedEndpoint = importedPreview.endpoint
            return connection().copy(certificateSha256 = importedPreview.certificateSha256)
        }
        override suspend fun clearPairing() { pairedEndpoint = null }
        override fun observeHistory() = history
        override suspend fun get(requestId: String) = records[requestId]
        override suspend fun generate(submit: IeltsGatewaySubmit): IeltsGenerationRecord {
            sent += submit; generationGate?.await()
            return IeltsGenerationRecord(submit = submit,
                submitHash = IeltsExerciseJson.canonicalSha256(IeltsGatewayProtocol.encodeSubmit(submit)),
                endpoint = requireNotNull(pairedEndpoint), expectedHealth = health, attemptId = "attempt-generated",
                revision = 1, createdAt = 100, updatedAt = 100, dispatchClaimed = !returnPrepared,
                stage = if (returnPrepared) IeltsGenerationStage.PREPARED else IeltsGenerationStage.QUEUED,
                issue = if (returnPrepared) IeltsGenerationIssue.OUTCOME_UNKNOWN else null).also { records[submit.requestId] = it; publish() }
        }
        override suspend fun reconcile(requestId: String): IeltsGenerationRecord { refreshes.incrementAndGet(); return records.getValue(requestId) }
        override suspend fun cancel(requestId: String): IeltsGenerationRecord = records.getValue(requestId)
        fun finish(requestId: String) {
            val previous = records.getValue(requestId)
            val practice = practices.seed(previous.attemptId, previous.submit)
            records[requestId] = previous.copy(packageHash = practice.packageHash, stage = IeltsGenerationStage.READY,
                revision = previous.revision + 1, issue = null)
            publish()
        }
        private fun publish() {
            history.value = records.values.map { record ->
                record.toHistoryItem()
            }
        }
    }
    private inner class Fixture(paired: Boolean) {
        val practices = Practices()
        val generations = Generations(practices, paired)
        val savedState = SavedStateHandle()
        lateinit var vm: IeltsExerciseViewModel
        var visible by mutableStateOf(true)
        val exits = AtomicInteger()
        var pixels48 = 0f
        var hostView: View? = null
        fun createModel() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val store = ViewModelStore(); stores += store
                vm = IeltsExerciseViewModel(generations, practices, savedState, clock = { 500 }, idFactory = { "route-generated-request" })
                store.put("ielts-route", vm)
            }
        }
        @Composable fun Content(theme: ThemeMode, fontScale: Float) {
            val density = LocalDensity.current
            val activity = LocalContext.current.findActivity()
            val view = LocalView.current
            SideEffect { hostView = view }
            DisposableEffect(activity) {
                val window = activity?.window
                val originalMode = window?.attributes?.softInputMode
                val previousLightStatus = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView).isAppearanceLightStatusBars }
                val previousLightNavigation = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView).isAppearanceLightNavigationBars }
                (activity as? ComponentActivity)?.enableEdgeToEdge()
                if (window != null && originalMode != null) window.setSoftInputMode(
                    (originalMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                onDispose {
                    if (window != null && originalMode != null) {
                        window.setSoftInputMode(originalMode)
                        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
                        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                        previousLightStatus?.let { controller.isAppearanceLightStatusBars = it }
                        previousLightNavigation?.let { controller.isAppearanceLightNavigationBars = it }
                    }
                }
            }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                pixels48 = with(LocalDensity.current) { 48.dp.toPx() }
                LexiconTheme(AppSettings(theme = theme)) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.width(320.dp).fillMaxHeight().testTag("route_test_viewport")) {
                            if (visible) IeltsExerciseRoute(vm, listOf(article)) { exits.incrementAndGet(); visible = false }
                            else Text("returned-to-reader", Modifier.testTag("route_test_closed"))
                        }
                    }
                }
            }
        }
    }
    private fun fixture(paired: Boolean = false, theme: ThemeMode = ThemeMode.LIGHT, scale: Float = 1f,
        seed: (Fixture) -> Unit = {}): Fixture {
        val result = Fixture(paired).also(seed)
        result.createModel()
        compose.setContent { result.Content(theme, scale) }
        compose.waitUntil(5000) { !result.vm.state.value.busy }
        return result
    }
    private fun routeNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNodeWithTag("ielts_workspace_list").performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo().assertIsDisplayed()
    }
    private fun practiceNode(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("ielts_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun gate() = CompletableDeferred<Unit>().also { gates += it }
    private fun connectButton() = hasText("连接") and hasClickAction()
    private fun historyButton(answered: Int = 0) = hasText("草稿 · $answered/4") and hasClickAction()

    private fun screenshot(variant: String, name: String, tag: String = "ielts_workspace") {
        val target = compose.onNodeWithTag(tag).assertIsDisplayed()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.filesDir, "validation-1.35.0/ielts-fixture/$variant/$screenshotRun")
        check(folder.isDirectory || folder.mkdirs())
        // captureToImage waits for the Compose node's draw before PixelCopy; a semantics-only idle can precede the first frame.
        val rendered = target.captureToImage().asAndroidBitmap()
        try { File(folder, name).outputStream().use { check(rendered.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { rendered.recycle() }
        instrumentation.waitForIdleSync(); SystemClock.sleep(100)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(folder, "screen-$name").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
    }

    private fun keyboardEvidence(fixture: Fixture) {
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "validation-1.35.0/ielts-fixture/history-keyboard/$screenshotRun")
        check(folder.isDirectory || folder.mkdirs())
        val back = compose.onNodeWithTag("ielts_back", useUnmergedTree = true).fetchSemanticsNode()
        val page = compose.onNodeWithTag("ielts_practice").fetchSemanticsNode()
        val editor = compose.onNodeWithTag("ielts_writing_input").fetchSemanticsNode()
        val facts = mutableListOf("back=${back.boundsInRoot}", "page=${page.boundsInRoot}", "editor=${editor.boundsInRoot}")
        compose.runOnIdle {
            val view = requireNotNull(fixture.hostView)
            val insets = ViewCompat.getRootWindowInsets(view)
            facts += "softInputMode=${view.context.findActivity()?.window?.attributes?.softInputMode}"
            facts += "imeVisible=${insets?.isVisible(WindowInsetsCompat.Type.ime())} imeBottom=${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom}"
            facts += "viewHeight=${view.height} rootHeight=${view.rootView.height}"
        }
        File(folder, "keyboard-layout.txt").writeText(facts.joinToString("\n"))
        screenshot("history-keyboard", "01-focused-editor.png", "ielts_practice")
    }

    /** Button semantics merge text; inspect both the control and its actual text descendants. */
    private fun controlTextLayouts(controlTag: String, label: String, variant: String): List<TextLayoutResult> {
        val result = mutableListOf<TextLayoutResult>()
        val diagnostics = mutableListOf<String>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.filesDir, "validation-1.35.0/ielts-fixture/$variant/$screenshotRun")
        check(folder.isDirectory || folder.mkdirs())
        try {
            compose.waitUntil(5000) {
                result.clear(); diagnostics.clear()
                val matcher = (hasTestTag(controlTag) or hasAnyAncestor(hasTestTag(controlTag))) and hasText(label)
                val nodes = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
                val merged = compose.onAllNodes(hasTestTag(controlTag)).fetchSemanticsNodes()
                compose.runOnIdle {
                    (nodes + merged).forEach { node ->
                        val local = mutableListOf<TextLayoutResult>()
                        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
                        val available = action?.invoke(local)
                        result += local.filter { it.layoutInput.text.text == label }
                        diagnostics += "node=${node.id} bounds=${node.boundsInRoot} action=${action != null} available=$available layouts=${local.size}"
                    }
                }
                result.isNotEmpty()
            }
        } finally {
            File(folder, "$controlTag-layout.txt").writeText(diagnostics.joinToString("\n") + "\n" + result.joinToString("\n") {
                "text=${it.layoutInput.text.text} fontScale=${it.layoutInput.density.fontScale} lines=${it.lineCount} overflow=${it.hasVisualOverflow} size=${it.size} widthOverflow=${it.didOverflowWidth} heightOverflow=${it.didOverflowHeight} paragraphWidth=${it.multiParagraph.width} paragraphHeight=${it.multiParagraph.height} constraints=${it.layoutInput.constraints} style=${it.layoutInput.style} lineRight=${it.getLineRight(0)} lineBottom=${it.getLineBottom(0)}"
            })
        }
        assertTrue("$controlTag must expose its rendered text layout", result.isNotEmpty())
        return result.toList()
    }

    private fun assertVisibleTextFits(layouts: List<TextLayoutResult>) {
        layouts.forEach { layout ->
            // Compose can reuse a paragraph measured at the available width and
            // report a smaller, content-sized Text. In that case hasVisualOverflow
            // compares unused paragraph space, not the visible glyph boundaries.
            assertTrue("All text must be laid out", layout.lineCount > 0 &&
                layout.getLineEnd(layout.lineCount - 1) == layout.layoutInput.text.length)
            repeat(layout.lineCount) { line ->
                assertFalse("No line may be ellipsized", layout.isLineEllipsized(line))
                assertTrue("Text must fit the left edge", layout.getLineLeft(line) >= -1f)
                assertTrue("Text must fit the right edge", layout.getLineRight(line) <= layout.size.width + 1f)
                assertTrue("Text must fit the bottom edge", layout.getLineBottom(line) <= layout.size.height + 1f)
            }
        }
    }

    @Test fun unpairedWorkspaceHasAnActionableEntryAndDoesNotGenerateOrRevealReferences() {
        val f = fixture()
        compose.onNodeWithTag("ielts_connection").assertIsDisplayed().assertIsEnabled().assertTextContains("连接电脑")
        routeNode(hasText("打开一篇原文，在阅读工具中选择“雅思练习”。")).assertIsDisplayed()
        compose.onNodeWithTag("ielts_generate").assertDoesNotExist()
        compose.onNodeWithTag("ielts_practice").assertDoesNotExist()
        compose.onNodeWithText("PRIVATE_ROUTE_REFERENCE", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertTrue(f.generations.sent.isEmpty()); assertTrue(f.practices.values.isEmpty()) }
    }

    @Test fun daylightPairingAtTwoHundredPercentKeepsFieldsAndActionsReachable() = pairingAtLargeType(ThemeMode.LIGHT)
    @Test fun draculaPairingAtTwoHundredPercentKeepsFieldsAndActionsReachable() = pairingAtLargeType(ThemeMode.DARK)

    private fun pairingAtLargeType(theme: ThemeMode) {
        val f = fixture(theme = theme, scale = 2f)
        val variant = if (theme == ThemeMode.DARK) "route-night-font200" else "route-day-font200"
        screenshot(variant, "01-workspace.png")
        val viewport = compose.onNodeWithTag("route_test_viewport").fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("ielts_connection").assertIsDisplayed()
        val headerBounds = header.fetchSemanticsNode().boundsInRoot
        assertTrue(headerBounds.left >= viewport.left - 1 && headerBounds.right <= viewport.right + 1)
        assertTrue(headerBounds.height >= f.pixels48 - 1 && headerBounds.width >= f.pixels48 - 1)
        val headerLayouts = controlTextLayouts("ielts_connection", "连接电脑", variant)
        assertVisibleTextFits(headerLayouts)
        assertEquals(2f, headerLayouts.first().layoutInput.density.fontScale, .01f)
        header.performClick()
        routeNode(hasTestTag("ielts_import_pairing")).assertIsEnabled()
        compose.onNodeWithTag("ielts_endpoint").assertDoesNotExist()
        routeNode(hasTestTag("ielts_manual_pairing")).performClick()
        routeNode(hasTestTag("ielts_endpoint"))
        screenshot(variant, "02-pairing-fields.png")
        routeNode(hasTestTag("ielts_endpoint")).performTextInput("https://paired.example/")
        val token = routeNode(hasTestTag("ielts_pairing_token"))
        token.assert(SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit)).performTextInput("synthetic-pairing-value")
        val button = routeNode(connectButton()).assertIsEnabled()
        screenshot(variant, "03-pairing-submit.png")
        val bounds = button.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.height >= f.pixels48 - 1 && bounds.width >= f.pixels48 - 1)
        assertTrue(bounds.left >= viewport.left - 1 && bounds.right <= viewport.right + 1)
        val layouts = controlTextLayouts("ielts_pair", "连接", variant)
        assertVisibleTextFits(layouts)
        assertEquals(2f, layouts.first().layoutInput.density.fontScale, .01f)
        button.performClick()
        compose.waitUntil(5000) { f.vm.state.value.connection.paired && !f.vm.state.value.busy }
        compose.onNodeWithTag("ielts_connection").assertTextContains("连接设置")
        compose.onNodeWithTag("ielts_pairing_token").assertDoesNotExist()
        screenshot(variant, "04-connected.png")
        compose.onNodeWithTag("ielts_connection").performClick()
        routeNode(hasText("断开配对") and hasClickAction()).assertIsEnabled().performClick()
        compose.waitUntil(5000) { !f.vm.state.value.connection.paired && !f.vm.state.value.busy }
        compose.runOnIdle { assertEquals(1, f.generations.pairs.get()); assertTrue(f.generations.sent.isEmpty()) }
    }

    @Test fun pairingCodeIsNotRestoredWithSavedUiStateAndRepeatedPairClicksAreBlocked() {
        val f = Fixture(false); f.createModel()
        val restore = StateRestorationTester(compose)
        restore.setContent { f.Content(ThemeMode.LIGHT, 1f) }
        compose.waitUntil(5000) { !f.vm.state.value.busy }
        compose.onNodeWithTag("ielts_connection").performClick()
        routeNode(hasTestTag("ielts_manual_pairing")).performClick()
        routeNode(hasTestTag("ielts_endpoint")).performTextInput("https://paired.example/")
        routeNode(hasTestTag("ielts_pairing_token")).performTextInput("secret-fixture")
        restore.emulateSavedInstanceStateRestore()
        val restored = routeNode(hasTestTag("ielts_pairing_token")).fetchSemanticsNode()
        assertEquals("", restored.config[SemanticsProperties.EditableText].text)
        routeNode(connectButton()).assertIsNotEnabled()
        routeNode(hasTestTag("ielts_pairing_token")).performTextInput("replacement-fixture")
        val blocker = gate(); f.generations.pairGate = blocker
        routeNode(connectButton()).performClick()
        compose.waitUntil(5000) { f.generations.pairs.get() == 1 }
        routeNode(connectButton()).assertIsNotEnabled()
        compose.runOnIdle { assertTrue(f.savedState.keys().isEmpty()) }
        blocker.complete(Unit)
        compose.waitUntil(5000) { f.vm.state.value.connection.paired }
        assertEquals(1, f.generations.pairs.get())
    }

    @Test fun failedPairingReconnectsWithTheCurrentEphemeralFormInsteadOfRefreshing() {
        val f = fixture()
        f.generations.failPair = true
        compose.onNodeWithTag("ielts_connection").performClick()
        routeNode(hasTestTag("ielts_manual_pairing")).performClick()
        routeNode(hasTestTag("ielts_endpoint")).performTextInput("https://paired.example/")
        routeNode(hasTestTag("ielts_pairing_token")).performTextInput("synthetic-retry-code")
        routeNode(connectButton()).performClick()
        compose.waitUntil(5000) { f.generations.pairs.get() == 1 && f.vm.state.value.error != null && !f.vm.state.value.busy }
        routeNode(hasTestTag("ielts_workspace_retry")).assertTextEquals("重新连接").assertIsEnabled()
        compose.onNodeWithText("RAW_PRIVATE_FAILURE", substring = true).assertDoesNotExist()
        assertFalse(f.vm.state.value.connection.paired)
        f.generations.failPair = false
        val blocker = gate(); f.generations.pairGate = blocker
        routeNode(hasTestTag("ielts_workspace_retry")).performClick()
        compose.waitUntil(5000) { f.generations.pairs.get() == 2 }
        routeNode(hasTestTag("ielts_pair")).assertIsNotEnabled()
        blocker.complete(Unit)
        compose.waitUntil(5000) { f.vm.state.value.connection.paired && !f.vm.state.value.busy }
        compose.runOnIdle {
            assertNull(f.vm.state.value.error)
            assertEquals("https://paired.example/", f.vm.state.value.connection.endpoint)
            assertEquals(0, f.generations.refreshes.get())
            assertTrue(f.savedState.keys().isEmpty())
        }
        compose.onNodeWithTag("ielts_pairing_token").assertDoesNotExist()
    }

    @Test fun daylightImportedPreviewAtTwoHundredPercentCanCancelAndExplicitlyConnect() = importedPairingAtLargeType(ThemeMode.LIGHT)
    @Test fun draculaImportedPreviewAtTwoHundredPercentCanCancelAndExplicitlyConnect() = importedPairingAtLargeType(ThemeMode.DARK)

    private fun importedPairingAtLargeType(theme: ThemeMode) {
        val f = fixture(paired = true, theme = theme, scale = 2f)
        val previous = f.vm.state.value.connection
        val variant = if (theme == ThemeMode.DARK) "imported-night-font200" else "imported-day-font200"
        compose.onNodeWithTag("ielts_connection").performClick()
        routeNode(hasTestTag("ielts_import_pairing")).assertIsEnabled()
        compose.onNodeWithTag("ielts_endpoint").assertDoesNotExist()
        // The picker boundary supplies bytes; this test exercises the real Route and ViewModel after that boundary.
        compose.runOnIdle {
            val document = pairingDocument
            try { f.vm.inspectPairing(document) } finally { document.fill(0) }
        }
        compose.waitUntil(5000) { f.vm.state.value.pairingPreview != null && !f.vm.state.value.busy }
        routeNode(hasTestTag("ielts_pairing_preview")).assertTextEquals(importedPreview.endpoint)
        val viewport = compose.onNodeWithTag("route_test_viewport").fetchSemanticsNode().boundsInRoot
        listOf("ielts_confirm_pairing" to "连接这台电脑", "ielts_discard_pairing" to "取消").forEach { (tag, label) ->
            val control = routeNode(hasTestTag(tag)).assertIsEnabled()
            val bounds = control.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height >= f.pixels48 - 1 && bounds.width >= f.pixels48 - 1)
            assertTrue(bounds.left >= viewport.left - 1 && bounds.right <= viewport.right + 1)
            val layouts = controlTextLayouts(tag, label, variant)
            assertVisibleTextFits(layouts)
            assertEquals(2f, layouts.first().layoutInput.density.fontScale, .01f)
        }
        screenshot(variant, "01-preview-controls.png")
        compose.runOnIdle {
            assertEquals(previous, f.vm.state.value.connection)
            assertEquals(1, f.generations.inspections.get())
            assertEquals(0, f.generations.importedPairs.get()); assertEquals(0, f.generations.pairs.get())
            assertTrue(f.generations.sent.isEmpty()); assertTrue(f.savedState.keys().isEmpty())
            assertFalse(f.vm.state.value.toString().contains(fileSecret))
        }
        compose.onNodeWithText(fileSecret, substring = true).assertDoesNotExist()
        routeNode(hasTestTag("ielts_discard_pairing")).performClick()
        compose.waitUntil(5000) { f.vm.state.value.pairingPreview == null }
        compose.runOnIdle {
            assertEquals(previous, f.vm.state.value.connection)
            assertEquals(previous.endpoint, f.generations.pairedEndpoint)
            assertEquals(0, f.generations.importedPairs.get())
        }
        compose.onNodeWithTag("ielts_confirm_pairing").assertDoesNotExist()

        routeNode(hasTestTag("ielts_manual_pairing")).performClick()
        routeNode(hasTestTag("ielts_endpoint")).assertIsDisplayed()
        routeNode(hasTestTag("ielts_pairing_token")).assertIsDisplayed()
        routeNode(hasTestTag("ielts_manual_pairing")).performClick()
        compose.onNodeWithTag("ielts_pairing_token").assertDoesNotExist()
        compose.runOnIdle { f.vm.inspectPairing(pairingDocument) }
        compose.waitUntil(5000) { f.vm.state.value.pairingPreview != null && !f.vm.state.value.busy }
        val blocker = gate(); f.generations.importedPairGate = blocker
        routeNode(hasTestTag("ielts_confirm_pairing")).performClick()
        compose.waitUntil(5000) { f.generations.importedPairs.get() == 1 }
        routeNode(hasTestTag("ielts_confirm_pairing")).assertIsNotEnabled()
        routeNode(hasTestTag("ielts_discard_pairing")).assertIsNotEnabled()
        blocker.complete(Unit)
        compose.waitUntil(5000) { f.vm.state.value.connection.endpoint == importedPreview.endpoint && !f.vm.state.value.busy }
        compose.onNodeWithTag("ielts_pairing_preview").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(importedPreview.certificateSha256, f.vm.state.value.connection.certificateSha256)
            assertEquals(1, f.generations.importedPairs.get()); assertEquals(0, f.generations.pairs.get())
            assertTrue(f.generations.sent.isEmpty()); assertTrue(f.savedState.keys().isEmpty())
        }
        screenshot(variant, "02-connected.png")
    }

    @Test fun importedPairingFailureKeepsThePreviewAndRetryUsesOnlyTheExplicitConfirmationPath() {
        val f = fixture()
        compose.onNodeWithTag("ielts_connection").performClick()
        compose.runOnIdle { f.vm.inspectPairing(pairingDocument) }
        compose.waitUntil(5000) { f.vm.state.value.pairingPreview != null && !f.vm.state.value.busy }
        assertEquals(0, f.generations.importedPairs.get())
        f.generations.failImportedPair = true
        routeNode(hasTestTag("ielts_confirm_pairing")).performClick()
        compose.waitUntil(5000) { f.generations.importedPairs.get() == 1 && f.vm.state.value.error != null && !f.vm.state.value.busy }
        routeNode(hasTestTag("ielts_pairing_preview")).assertTextEquals(importedPreview.endpoint)
        routeNode(hasTestTag("ielts_workspace_retry")).assertTextEquals("重新连接").assertIsEnabled()
        compose.onNodeWithText(fileSecret, substring = true).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(importedPreview, f.vm.state.value.pairingPreview)
            assertFalse(f.vm.state.value.connection.paired)
            assertTrue(f.savedState.keys().isEmpty()); assertTrue(f.generations.sent.isEmpty())
        }
        f.generations.failImportedPair = false
        val blocker = gate(); f.generations.importedPairGate = blocker
        routeNode(hasTestTag("ielts_workspace_retry")).performClick()
        compose.waitUntil(5000) { f.generations.importedPairs.get() == 2 }
        routeNode(hasTestTag("ielts_confirm_pairing")).assertIsNotEnabled()
        blocker.complete(Unit)
        compose.waitUntil(5000) { f.vm.state.value.connection.paired && !f.vm.state.value.busy }
        compose.runOnIdle {
            assertNull(f.vm.state.value.pairingPreview); assertNull(f.vm.state.value.error)
            assertEquals(2, f.generations.importedPairs.get()); assertEquals(0, f.generations.pairs.get())
            assertEquals(0, f.generations.refreshes.get()); assertTrue(f.generations.sent.isEmpty())
        }
    }

    @Test fun preparedArticleGeneratesOnceThenTheReadyResultOpensTheOriginalPractice() {
        val f = fixture(paired = true)
        compose.runOnIdle { f.vm.prepare(article, body, true) }
        compose.waitUntil(5000) { f.vm.state.value.canGenerate }
        val blocker = gate(); f.generations.generationGate = blocker
        routeNode(hasTestTag("ielts_generate")).assertIsEnabled().performClick()
        compose.waitUntil(5000) { f.generations.sent.size == 1 }
        routeNode(hasTestTag("ielts_generate")).assertIsNotEnabled()
        blocker.complete(Unit)
        compose.waitUntil(5000) { f.vm.state.value.generation?.stage == IeltsGenerationStage.QUEUED }
        routeNode(hasTestTag("ielts_job_state")).assertTextContains("等待生成", substring = true)
        val sent = f.generations.sent.single()
        assertEquals(original, sent.body)
        assertEquals(IeltsCompletionRequest("q4", 1, false), sent.readingRequests.last())
        sent.paragraphs.forEach { assertEquals(it.text, original.substring(it.start, it.end)) }
        compose.runOnIdle { f.generations.finish(sent.requestId) }
        routeNode(hasText("刷新状态") and hasClickAction()).performClick()
        compose.waitUntil(5000) { f.vm.state.value.candidate != null }
        compose.onNodeWithTag("ielts_practice").assertIsDisplayed()
        compose.onNodeWithTag("ielts_submit").assertIsNotEnabled()
        compose.onNodeWithText("PRIVATE_ROUTE_REFERENCE", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, f.generations.sent.size); assertEquals(1, f.practices.values.size) }
    }

    @Test fun knownUnsentPreparedRequestRetainsItsExplicitGenerateAction() {
        val f = fixture(paired = true)
        f.generations.returnPrepared = true
        compose.runOnIdle { f.vm.prepare(article, body, true) }
        compose.waitUntil(5000) { f.vm.state.value.canGenerate }
        routeNode(hasTestTag("ielts_generate")).performClick()
        compose.waitUntil(5000) { f.vm.state.value.generation?.stage == IeltsGenerationStage.PREPARED && !f.vm.state.value.busy }
        assertTrue(f.vm.state.value.canGenerate)
        f.generations.returnPrepared = false
        routeNode(hasTestTag("ielts_generate")).assertIsEnabled().performClick()
        compose.waitUntil(5000) { f.vm.state.value.generation?.stage == IeltsGenerationStage.QUEUED }
        compose.runOnIdle {
            assertEquals(2, f.generations.sent.size)
            assertEquals(f.generations.sent.first(), f.generations.sent.last())
            assertEquals(1, f.generations.records.size)
        }
    }

    @Test fun selectedWaitingTaskDoesNotRepeatInTheGenerationHistory() {
        val f = fixture(paired = true, scale = 2f)
        compose.runOnIdle { f.vm.prepare(article, body, true) }
        compose.waitUntil(5000) { f.vm.state.value.canGenerate }
        routeNode(hasTestTag("ielts_generate")).performClick()
        compose.waitUntil(5000) { f.vm.state.value.generation?.stage == IeltsGenerationStage.QUEUED && !f.vm.state.value.busy }
        val requestId = requireNotNull(f.vm.state.value.generation?.requestId)
        assertEquals(1, f.vm.state.value.generationHistory.size)
        compose.onNodeWithTag("ielts_job_state").assertTextEquals("[queued] 等待生成")
        compose.onNodeWithTag("ielts_generation_$requestId").assertDoesNotExist()
        compose.onNodeWithText("生成记录").assertDoesNotExist()
        routeNode(hasTestTag("ielts_refresh")).assertIsEnabled()
        routeNode(hasTestTag("ielts_cancel")).assertIsEnabled()
        screenshot("waiting-once-day200", "01-single-current-task.png")
        assertEquals(1, f.generations.sent.size)
    }

    private fun terminalRecord(stage: IeltsGenerationStage, error: String? = null, reaped: Int = 1): IeltsGenerationRecord {
        val submit = payload("route-finished-request")
        val jobState = if (stage == IeltsGenerationStage.CANCELLED) IeltsGatewayJobState.CANCELLED else IeltsGatewayJobState.FAILED
        return IeltsGenerationRecord(submit = submit,
            submitHash = IeltsExerciseJson.canonicalSha256(IeltsGatewayProtocol.encodeSubmit(submit)),
            endpoint = "https://paired.example/", expectedHealth = health, attemptId = "previous-unused-attempt",
            revision = 3, createdAt = 100, updatedAt = 200, dispatchClaimed = true, stage = stage,
            job = IeltsGatewayJob("job-terminal", submit.requestId, "d".repeat(64), jobState, 3, 100, 200, error, reaped))
    }

    @Test fun failedRequestCanRegenerateFromHistoryAtLargeDaylightType() = terminalRegeneration(ThemeMode.LIGHT, IeltsGenerationStage.FAILED)
    @Test fun cancelledRequestCanRegenerateFromHistoryAtLargeDraculaType() = terminalRegeneration(ThemeMode.DARK, IeltsGenerationStage.CANCELLED)

    private fun terminalRegeneration(theme: ThemeMode, stage: IeltsGenerationStage) {
        val previous = terminalRecord(stage)
        val variant = if (theme == ThemeMode.LIGHT) "regenerate-day200" else "regenerate-night200"
        lateinit var retainedPractice: IeltsSavedPractice
        val f = fixture(paired = true, theme = theme, scale = 2f) {
            it.generations.records[previous.requestId] = previous
            it.savedState[IeltsExerciseViewModel.REQUEST_KEY] = previous.requestId
            retainedPractice = it.practices.seed("unrelated-retained-practice")
        }
        compose.waitUntil(5000) { f.vm.state.value.canRegenerate }
        val button = routeNode(hasTestTag("ielts_regenerate"))
        button.assertIsEnabled().assertTextContains("重新生成")
        assertVisibleTextFits(controlTextLayouts("ielts_regenerate", "重新生成", variant))
        screenshot(variant, "01-terminal-action.png")
        val blocker = gate(); f.generations.generationGate = blocker
        button.performClick()
        compose.waitUntil(5000) { f.generations.sent.size == 1 }
        routeNode(hasTestTag("ielts_regenerate")).assertIsNotEnabled()
        compose.runOnIdle { f.vm.regenerate() }
        assertEquals(1, f.generations.sent.size)
        blocker.complete(Unit)
        compose.waitUntil(5000) { !f.vm.state.value.busy && f.vm.state.value.generation?.stage == IeltsGenerationStage.QUEUED }
        val sent = f.generations.sent.single()
        assertNotEquals(previous.requestId, sent.requestId)
        assertEquals(previous.submit.copy(requestId = sent.requestId), sent)
        assertEquals(previous, f.generations.records[previous.requestId])
        assertEquals(retainedPractice, f.practices.values[retainedPractice.attemptId])
        assertEquals(2, f.generations.records.size)
        routeNode(hasTestTag("ielts_refresh")).performClick()
        compose.waitUntil(5000) { !f.vm.state.value.busy }
        assertEquals(1, f.generations.sent.size)
        assertEquals(sent.requestId, f.savedState.get<String>(IeltsExerciseViewModel.REQUEST_KEY))
        screenshot(variant, "02-new-request.png")
        compose.onNodeWithTag("ielts_generation_${sent.requestId}").assertDoesNotExist()
        routeNode(hasTestTag("ielts_generation_${previous.requestId}")).assertIsDisplayed()
        compose.runOnIdle { f.vm.openGeneration(previous.requestId) }
        compose.waitUntil(5000) { f.vm.state.value.generation?.requestId == previous.requestId && !f.vm.state.value.busy }
        compose.onNodeWithTag("ielts_generation_${previous.requestId}").assertDoesNotExist()
        routeNode(hasTestTag("ielts_generation_${sent.requestId}")).assertIsDisplayed()
        assertEquals(1, f.generations.sent.size)
    }

    @Test fun upstreamUnknownCompletionKeepsOnlyTheOriginalRequestLookup() {
        val previous = terminalRecord(IeltsGenerationStage.FAILED, "provider_completion_unknown")
        val f = fixture(paired = true) {
            it.generations.records[previous.requestId] = previous
            it.savedState[IeltsExerciseViewModel.REQUEST_KEY] = previous.requestId
        }
        assertFalse(f.vm.state.value.canRegenerate)
        compose.onNodeWithTag("ielts_job_state").assertTextEquals("[checking] 原请求结果待确认")
        compose.onNodeWithText("[failed] 本次生成未通过").assertDoesNotExist()
        compose.onNodeWithTag("ielts_regenerate").assertDoesNotExist()
        routeNode(hasTestTag("ielts_refresh")).performClick()
        compose.waitUntil(5000) { !f.vm.state.value.busy }
        assertTrue(f.generations.sent.isEmpty())
        assertEquals(previous, f.generations.records[previous.requestId])
        assertEquals(previous.requestId, f.savedState.get<String>(IeltsExerciseViewModel.REQUEST_KEY))
    }

    @Test fun historyDraftOpensOfflineAndBackWaitsUntilLatestEditsAreSaved() {
        val f = fixture(seed = { it.practices.seed() })
        routeNode(historyButton()).performClick()
        compose.waitUntil(5000) { f.vm.state.value.candidate != null }
        val blocker = gate(); f.practices.saveGate = blocker
        practiceNode("ielts_option_q1_B").performClick()
        practiceNode("ielts_writing_input").performTextInput("A retained draft from the reading session.")
        keyboardEvidence(f)
        compose.onNodeWithTag("ielts_back").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { f.vm.state.value.isSaving }
        assertEquals(0, f.exits.get())
        blocker.complete(Unit)
        compose.waitUntil(5000) { f.exits.get() == 1 }
        compose.onNodeWithTag("route_test_closed").assertIsDisplayed()
        val saved = f.practices.values.getValue("attempt-history")
        assertEquals("B", saved.draftResponses.single().answer)
        assertEquals("A retained draft from the reading session.", saved.writingText)
        assertNull(saved.submission)
        compose.runOnIdle { assertTrue(f.savedState.keys().isEmpty()); f.visible = true }
        routeNode(historyButton(1)).performClick()
        compose.waitUntil(5000) { f.vm.state.value.candidate != null }
        practiceNode("ielts_writing_input").assertTextContains(saved.writingText)
        compose.onNodeWithTag("ielts_review_toggle_q1").assertDoesNotExist()
        compose.runOnIdle { assertTrue(f.generations.sent.isEmpty()) }
    }

    @Test fun failedBackSaveKeepsTheDraftOnScreenAndShowsOnlyTheSafeRetryMessage() {
        val f = fixture(seed = { it.practices.seed() })
        routeNode(historyButton()).performClick()
        compose.waitUntil(5000) { f.vm.state.value.candidate != null }
        f.practices.failSave = true
        practiceNode("ielts_writing_input").performTextInput("Keep this input after failure.")
        compose.onNodeWithTag("ielts_back").performClick()
        compose.waitUntil(5000) { f.vm.state.value.error != null && !f.vm.state.value.isSaving }
        compose.onNodeWithTag("ielts_practice").assertIsDisplayed()
        compose.onNodeWithTag("ielts_error").assertTextContains("当前输入已保留", substring = true)
        compose.onNodeWithText("RAW_PRIVATE_FAILURE", substring = true).assertDoesNotExist()
        assertEquals(0, f.exits.get())
        assertEquals("Keep this input after failure.", f.vm.state.value.writingText)
        f.practices.failSave = false
        compose.onNodeWithTag("ielts_retry").performClick()
        compose.waitUntil(5000) { !f.vm.state.value.dirty && !f.vm.state.value.isSaving }
        compose.onNodeWithTag("ielts_back").performClick()
        compose.waitUntil(5000) { f.exits.get() == 1 }
        assertEquals("Keep this input after failure.", f.practices.values.getValue("attempt-history").writingText)
    }

    @Test fun unavailableHistoryDoesNotOfferAFakeResumeOrExposePrivateStorageText() {
        val f = fixture(seed = {
            it.practices.history.value = listOf(IeltsPracticeHistoryItem("broken", null, null, null,
                IeltsPracticeHistoryStatus.UNAVAILABLE, null, null, null, null, null))
        })
        routeNode(hasText("记录暂不可读") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithTag("ielts_practice").assertDoesNotExist()
        compose.runOnIdle { assertTrue(f.practices.values.isEmpty()); assertTrue(f.generations.sent.isEmpty()) }
    }
}
