@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lexicon.ide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.ArticleStudy
import dev.lexicon.domain.ReadingQuestion
import dev.lexicon.domain.ReadingStudyScopes

private data class ComprehensionDraft(
    val identity: String = "",
    val answers: Map<String, String> = emptyMap(),
    val revealed: Set<String> = emptySet(),
) {
    companion object {
        val Saver = listSaver<ComprehensionDraft, Any>(
            save = { listOf(it.identity, it.answers.keys.toList(), it.answers.values.toList(), it.revealed.toList()) },
            restore = { values ->
                val ids = (values[1] as List<*>).map { it as String }
                val answers = (values[2] as List<*>).map { it as String }
                ComprehensionDraft(values[0] as String, ids.zip(answers).toMap(), (values[3] as List<*>).map { it as String }.toSet())
            },
        )
    }
}

/** A missing snapshot hides questions without discarding their draft; a different study resets it. */
@Composable
internal fun ReadingComprehensionQuestions(
    study: ArticleStudy?, canCheck: Boolean, onCheck: (ReadingQuestion, String) -> Unit,
) {
    var saved by rememberSaveable(stateSaver = ComprehensionDraft.Saver) { mutableStateOf(ComprehensionDraft()) }
    if (study == null) return
    val identity = remember(study) { ReadingStudyScopes.identity(study) }
    val draft = saved.takeIf { it.identity == identity } ?: ComprehensionDraft(identity)
    SideEffect { if (saved.identity != identity) saved = draft }
    study.questions.forEach { question -> key(question.id) {
        val answer = draft.answers[question.id].orEmpty()
        val reveal = question.id in draft.revealed
        Text(question.question, style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(answer, { saved = draft.copy(answers = draft.answers + (question.id to it.take(2000))) },
            Modifier.fillMaxWidth(), label = { Text("用自己的话回答") }, minLines = 2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onCheck(question, answer) }, enabled = canCheck && answer.isNotBlank()) { Text("检查理解") }
            TextButton(onClick = { saved = draft.copy(revealed = if (reveal) draft.revealed - question.id else draft.revealed + question.id) }) {
                Text(if (reveal) "收起提示" else "参考思路")
            }
        }
        if (reveal) Text(question.answer, style = MaterialTheme.typography.bodySmall)
    } }
}
