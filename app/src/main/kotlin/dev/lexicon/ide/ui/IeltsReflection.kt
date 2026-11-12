package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.*

internal fun ieltsStudyKind(kind: IeltsReadingType): String = when (kind) {
    IeltsReadingType.MULTIPLE_CHOICE -> "单项选择"
    IeltsReadingType.IDENTIFYING_INFORMATION -> "信息判断"
    IeltsReadingType.IDENTIFYING_WRITERS_VIEWS -> "作者观点"
    IeltsReadingType.SHORT_TEXT_COMPLETION -> "原文填空"
}

@Composable internal fun IeltsReflection(questionId: String, feedback: IeltsStudyFeedback?, busy: Boolean,
    onReflect: (IeltsStudyIntent?) -> Unit) {
    var expanded by rememberSaveable(questionId) { mutableStateOf(false) }
    val label = when (feedback?.intent) {
        IeltsStudyIntent.PRACTICE_AGAIN -> "已选再练此类"
        IeltsStudyIntent.QUESTIONABLE -> "题目存疑"
        null -> "个人复盘"
    }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .testTag("ielts_reflection_$questionId")) { Text(label) }
    IeltsDisclosure(expanded) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (feedback?.intent != IeltsStudyIntent.PRACTICE_AGAIN) TextButton(onClick = { onReflect(IeltsStudyIntent.PRACTICE_AGAIN) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ielts_again_$questionId")) { Text("下次再练此类") }
            if (feedback?.intent != IeltsStudyIntent.QUESTIONABLE) TextButton(onClick = { onReflect(IeltsStudyIntent.QUESTIONABLE) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ielts_questionable_$questionId")) { Text("标记题目存疑") }
            if (feedback?.intent != null) TextButton(onClick = { onReflect(null) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ielts_undo_reflection_$questionId")) { Text("撤销标记") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun IeltsStudyPreparation(state: IeltsExerciseUiState, onBalanced: () -> Unit,
    onOpenAttempt: (String) -> Unit) {
    val profile = state.studyRequest?.profile ?: state.study?.profile ?: return
    val focus = profile.focus ?: return
    var evidence by rememberSaveable(focus.attemptId, focus.questionId, focus.revision) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("专项 · ${ieltsStudyKind(focus.kind)}", Modifier.testTag("ielts_study_focus"), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { evidence = !evidence }, modifier = Modifier.heightIn(min = 48.dp).testTag("ielts_study_evidence")) { Text("选择依据") }
            if (state.studyRequest == null) TextButton(onClick = onBalanced, enabled = !state.busy && !state.studyBusy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("ielts_study_balanced")) { Text("恢复综合") }
        }
        IeltsDisclosure(evidence) {
            Column {
                Text("来自你对「${ieltsStudyKind(focus.kind)}」的再练选择。本次仍为 4 道阅读题。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onOpenAttempt(focus.attemptId) }, enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("ielts_study_open_origin")) { Text("查看原复盘") }
            }
        }
    }
}
