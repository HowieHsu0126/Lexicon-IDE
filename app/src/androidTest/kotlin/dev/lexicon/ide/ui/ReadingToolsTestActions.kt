package dev.lexicon.ide.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo

/** A pinned control has no scrollable parent; offscreen content still must be brought into view. */
internal fun SemanticsNodeInteraction.reachReadingTool(): SemanticsNodeInteraction {
    assertExists()
    if (runCatching { assertIsDisplayed() }.isFailure) performScrollTo()
    return this
}
