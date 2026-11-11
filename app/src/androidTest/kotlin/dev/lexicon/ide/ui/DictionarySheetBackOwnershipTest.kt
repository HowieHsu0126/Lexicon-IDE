@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.lexicon.ide.ui

import androidx.activity.BackEventCompat
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the installed libraries' dialog dispatcher. This is not an OS edge-gesture performance test. */
@RunWith(AndroidJUnit4::class)
class DictionarySheetBackOwnershipTest {
    @get:Rule val compose = createComposeRule()

    @Test fun anInnerPredictiveHandlerUsesTheDialogAndDoesNotHideItsParentSheet() {
        var owner: OnBackPressedDispatcherOwner? = null
        var navigationOwner: NavigationEventDispatcherOwner? = null
        var activityOwner: OnBackPressedDispatcherOwner? = null
        var sheet: SheetState? = null
        var visible by mutableStateOf(true)
        var inner by mutableStateOf(true)
        var progress by mutableFloatStateOf(0f)
        var cancellations = 0
        var innerPops = 0
        var dismissals = 0
        compose.setContent {
            LexiconTheme(AppSettings()) {
                val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                if (visible) ModalBottomSheet(
                    onDismissRequest = { dismissals++; visible = false },
                    sheetState = state,
                    properties = ModalBottomSheetProperties(shouldDismissOnBackPress = true),
                ) {
                    val localOwner = LocalOnBackPressedDispatcherOwner.current
                    val localNavigationOwner = LocalNavigationEventDispatcherOwner.current
                    val localActivity = LocalContext.current.findActivity()
                    SideEffect {
                        owner = localOwner; navigationOwner = localNavigationOwner
                        activityOwner = localActivity as? OnBackPressedDispatcherOwner; sheet = state
                    }
                    // Register in the dialog content and retain this registration when the page changes.
                    PredictiveBackHandler(enabled = inner) { events ->
                        try {
                            events.collect { progress = it.progress }
                            inner = false; innerPops++
                        } catch (_: CancellationException) { cancellations++ }
                        finally { progress = 0f }
                    }
                    Column(Modifier.fillMaxWidth().height(260.dp).testTag("probe_capture_sheet")) {
                        Text(if (inner) "完整释义" else "查词", Modifier.testTag("probe_page"))
                        Text("The parent capture remains in the same dialog.")
                    }
                }
            }
        }
        compose.waitUntil(5_000) { owner != null && sheet?.currentValue == SheetValue.Expanded }
        compose.runOnIdle {
            assertTrue("The inner handler must belong to the actual sheet's ComponentDialog", owner is ComponentDialog)
            assertNotNull(activityOwner)
            assertNotSame("An Activity handler would not prove interception inside the dialog", activityOwner, owner)
            assertSame("Activity Compose 1.13 prefers the navigation-event owner", owner, navigationOwner)
            assertSame((owner as ComponentDialog).navigationEventDispatcher,
                requireNotNull(navigationOwner).navigationEventDispatcher)
        }
        val dispatcher = requireNotNull(owner).onBackPressedDispatcher
        fun startGesture(value: Float) {
            compose.runOnIdle {
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
                dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 300f, value, BackEventCompat.EDGE_LEFT))
            }
            compose.waitUntil(5_000) { progress >= value - .01f }
        }
        fun assertParentVisible() = compose.runOnIdle {
            assertTrue(visible); assertTrue(requireNotNull(sheet).isVisible)
            assertEquals(SheetValue.Expanded, sheet?.currentValue)
            assertEquals(SheetValue.Expanded, sheet?.targetValue)
            assertEquals(0, dismissals)
        }

        startGesture(.55f)
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.waitUntil(5_000) { cancellations == 1 && progress == 0f }
        compose.runOnIdle { assertTrue(inner); assertEquals(0, innerPops) }
        assertParentVisible()

        startGesture(.75f)
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitUntil(5_000) { !inner && innerPops == 1 && progress == 0f }
        assertParentVisible()
        compose.onNodeWithTag("probe_capture_sheet").assertExists()

        // Once at the parent page, the disabled inner handler leaves native sheet dismissal intact.
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 300f, .75f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        compose.mainClock.advanceTimeBy(800)
        compose.waitUntil(5_000) { !visible && dismissals == 1 }
        compose.runOnIdle { assertEquals(1, innerPops); assertEquals(1, cancellations) }
    }
}
