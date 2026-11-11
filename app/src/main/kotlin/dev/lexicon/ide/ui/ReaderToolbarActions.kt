@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.StudyMode

/** Keep natural label widths; wrap whole actions when the current window cannot fit one row. */
@Composable
internal fun ReaderToolbarActions(
    mode: StudyMode,
    onBack: () -> Unit,
    onMode: () -> Unit,
    onTools: () -> Unit,
    onAppearance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp).testTag("reader_toolbar_actions"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).testTag("reader_toolbar_exit")) {
            Text("← 退出 Zen")
        }
        TextButton(onClick = onMode, modifier = Modifier.heightIn(min = 48.dp).testTag("reader_toolbar_mode")) {
            Text(if (mode == StudyMode.SKIM) "略读" else "精读")
        }
        TextButton(onClick = onTools, modifier = Modifier.heightIn(min = 48.dp).testTag("reader_toolbar_tools")) {
            Text("阅读工具")
        }
        TextButton(onClick = onAppearance, modifier = Modifier.heightIn(min = 48.dp).testTag("reader_toolbar_appearance")
            .semantics { contentDescription = "阅读外观" }) {
            Text("Aa")
        }
    }
}
