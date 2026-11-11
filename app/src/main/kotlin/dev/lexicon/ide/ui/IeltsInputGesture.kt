package dev.lexicon.ide.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** A nested text editor can hand scrolling to its parent. Preserve its input session
 * for the entire gesture, even at a scroll boundary; never consume selection events. */
internal class IeltsInputGesture {
    var beganInsideEditor = false
}

internal fun Modifier.observeIeltsInputStart(gesture: IeltsInputGesture, insideEditor: Boolean): Modifier =
    pointerInput(gesture, insideEditor) {
        awaitEachGesture {
            // Initial passes travel from parent to child. The page resets first,
            // then an editor on the hit path marks the same pointer sequence.
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gesture.beganInsideEditor = insideEditor
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
        }
    }
