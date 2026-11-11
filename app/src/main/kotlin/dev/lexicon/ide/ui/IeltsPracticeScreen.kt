package dev.lexicon.ide.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.lexicon.domain.*

/**
 * Stateless practice data and callbacks; only local disclosure/scroll state lives here.
 * The owner confirms a persisted submission before supplying answerView. This screen
 * does not call a model, write an attempt or install another predictive-back handler.
 * An empty completion callback clears that draft response; the owner can use clearAnswer.
 */
@Composable
internal fun IeltsPracticeScreen(
    candidate: IeltsCandidateView,
    draftResponses: List<IeltsResponse>,
    writingText: String,
    isSaving: Boolean,
    answerView: IeltsAnswerView?,
    error: String?,
    onAnswer: (String, String) -> Unit,
    onWriting: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    writingTask1Text: String? = null,
    onWritingTask1: (String) -> Unit = {},
) {
    key(candidate.packageSha256) {
        val matches = answerView == null || (
            answerView.packageSha256 == candidate.packageSha256 &&
                answerView.readingAnswers.map { it.questionId } == candidate.readingQuestions.map { it.questionId } &&
                answerView.readingAnswers.zip(candidate.readingQuestions).all { (answer, question) -> answer.kind == ieltsKind(question) })
        val review = answerView?.takeIf { matches && !isSaving }
        val locked = isSaving || answerView != null
        val responseMap = remember(draftResponses) {
            draftResponses.groupBy { it.questionId }.mapValues { (_, values) -> values.singleOrNull()?.answer }
        }
        val completed = candidate.readingQuestions.count { question -> ieltsResponseComplete(question, responseMap[question.questionId]) }
        val allAnswered = completed == candidate.readingQuestions.size && candidate.readingQuestions.isNotEmpty() &&
            draftResponses.size == candidate.readingQuestions.size && draftResponses.all { answer -> candidate.readingQuestions.any { it.questionId == answer.questionId } }
        val writingWords = remember(writingText) { runCatching { IeltsExerciseValidator.wordCount(writingText) }.getOrNull() }
        val task1 = candidate.writingTask1.task
        val writing1Words = remember(writingTask1Text) { writingTask1Text?.let { runCatching { IeltsExerciseValidator.wordCount(it) }.getOrNull() } }
        val canSubmit = !locked && allAnswered && writingWords != null && (task1 == null || writing1Words != null)
        val actualError = if (!matches) "回看记录与当前练习不一致，请重新读取。" else error
        var sourceExpanded by rememberSaveable { mutableStateOf(false) }
        val scroll = rememberLazyListState()
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val inputGesture = remember { IeltsInputGesture() }
        val finishInput = remember(focus, keyboard) { { focus.clearFocus(); keyboard?.hide(); Unit } }
        LaunchedEffect(scroll, finishInput, inputGesture) {
            // Editor-originated drags can propagate to the outer list. Do not
            // treat them, or programmatic scrolling, as leaving writing mode.
            scroll.interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Start && !inputGesture.beganInsideEditor) finishInput()
            }
        }
        LaunchedEffect(review?.submissionSha256) {
            if (review != null) { finishInput(); scroll.animateScrollToItem(0) }
        }

        Surface(modifier.fillMaxSize().observeIeltsInputStart(inputGesture, false).testTag("ielts_practice"),
            color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(if (review == null) "雅思练习 · AI" else "练习回看", style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.testTag("ielts_title"))
                    }
                    TextButton(onClick = onBack, enabled = !isSaving,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("ielts_back")) { Text("返回") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("ielts_content"), state = scroll,
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item(key = "article") {
                        Column(Modifier.fillMaxWidth()) {
                            TextButton(onClick = { finishInput(); sourceExpanded = !sourceExpanded },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ielts_source_toggle")) {
                                Text(if (sourceExpanded) "收起原文" else "阅读原文")
                            }
                            IeltsDisclosure(sourceExpanded) {
                                SelectionContainer {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("ielts_source"),
                                        verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                        candidate.paragraphs.forEach { paragraph ->
                                            Text(paragraph.text, Modifier.fillMaxWidth().testTag("ielts_source_${paragraph.paragraphId}"),
                                                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = ReadingSerif,
                                                    lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.6f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    itemsIndexed(candidate.readingQuestions, key = { _, question -> "question:${question.questionId}" }) { index, question ->
                        val reviewItem = review?.readingAnswers?.get(index)
                        IeltsQuestionCard(index + 1, question, reviewItem?.submittedAnswer ?: responseMap[question.questionId].orEmpty(),
                            editable = !locked, review = reviewItem, submissionIdentity = review?.submissionSha256,
                            paragraphs = candidate.paragraphs, finishInput = finishInput, inputGesture = inputGesture,
                            onAnswer = { value -> if (!locked) onAnswer(question.questionId, value) })
                    }
                    if (task1 != null) item(key = "writing1") {
                        IeltsVisualWritingCard(task1, candidate.paragraphs, writingTask1Text.orEmpty(), writing1Words,
                            editable = !locked, reviewed = review != null, onWriting = { if (!locked) onWritingTask1(it) },
                            onReadEvidence = finishInput, inputGesture = inputGesture)
                    }
                    if (candidate.writingTask1.reason == "insufficient_comparable_data") item(key = "writing1_unavailable") {
                        Text("Task 1 · 原文缺少适合图表比较的数据", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item(key = "writing") {
                        IeltsWritingCard(candidate.writingTask2, writingText, writingWords, editable = !locked,
                            reviewed = review != null, onWriting = { if (!locked) onWriting(it) },
                            inputGesture = inputGesture)
                    }
                    item(key = "end") { Spacer(Modifier.height(8.dp)) }
                }
                if (review == null || actualError != null) {
                  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    actualError?.let { message ->
                        Text(message, Modifier.fillMaxWidth().testTag("ielts_error"), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onRetry, enabled = !isSaving,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ielts_retry")) { Text("重试") }
                    }
                    if ((review == null && answerView == null) || isSaving) {
                        Text(if (isSaving) "正在保存作答…" else "阅读已答 $completed / ${candidate.questionCount}",
                            Modifier.testTag("ielts_progress"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { if (canSubmit) onSubmit() }, enabled = canSubmit,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ielts_submit")) {
                            Text(if (isSaving) "保存中…" else "提交作答")
                        }
                    }
                  }
                }
            }
        }
    }
}

@Composable
private fun IeltsQuestionCard(number: Int, question: IeltsCandidateQuestion, response: String, editable: Boolean,
    review: IeltsAnswerItem?, submissionIdentity: String?, paragraphs: List<IeltsParagraph>, finishInput: () -> Unit,
    inputGesture: IeltsInputGesture,
    onAnswer: (String) -> Unit) {
    val selectAnswer: (String) -> Unit = { finishInput(); onAnswer(it) }
    Surface(Modifier.fillMaxWidth().testTag("ielts_question_${question.questionId}"),
        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("第 $number 题 · ${ieltsTypeLabel(question)}", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(question.prompt, Modifier.fillMaxWidth().testTag("ielts_prompt_${question.questionId}"),
                style = MaterialTheme.typography.bodyLarge)
            when (question) {
                is IeltsCandidateChoice -> IeltsOptions(question.questionId, question.options.map { it.optionId.name to "${it.optionId.name}. ${it.text}" }, response, editable, selectAnswer)
                is IeltsCandidateInformation -> IeltsOptions(question.questionId,
                    question.responseValues.map { it.wire to ieltsInformationLabel(it.wire) }, response, editable, selectAnswer)
                is IeltsCandidateViewQuestion -> IeltsOptions(question.questionId,
                    question.responseValues.map { it.wire to ieltsViewLabel(it.wire) }, response, editable, selectAnswer)
                is IeltsCandidateCompletion -> {
                    Text("从原文填写 · 最多 ${question.maxWords} 词${if (question.allowNumbers) "，可含数字" else "，不含数字"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("ielts_limit_${question.questionId}"))
                    OutlinedTextField(value = response, onValueChange = onAnswer, enabled = editable || review != null,
                        readOnly = !editable, label = { Text(if (review == null) "填写答案" else "你的作答") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().observeIeltsInputStart(inputGesture, true).testTag("ielts_input_${question.questionId}"),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { finishInput() }),
                        shape = RoundedCornerShape(8.dp))
                }
            }
            if (review != null) IeltsQuestionReview(question, review, requireNotNull(submissionIdentity), paragraphs)
        }
    }
}

@Composable
private fun IeltsOptions(questionId: String, options: List<Pair<String, String>>, response: String, editable: Boolean,
    onAnswer: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) ->
            val selected = response == value
            Surface(shape = RoundedCornerShape(8.dp),
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected, enabled = editable, role = Role.RadioButton, onClick = { onAnswer(value) })
                    .padding(horizontal = 8.dp, vertical = 8.dp).testTag("ielts_option_${questionId}_$value"),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected, onClick = null, enabled = editable)
                    Text(label, Modifier.weight(1f).padding(start = 8.dp).testTag("ielts_option_text_${questionId}_$value"),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun IeltsQuestionReview(question: IeltsCandidateQuestion, review: IeltsAnswerItem, submissionIdentity: String,
    paragraphs: List<IeltsParagraph>) {
    var expanded by rememberSaveable(question.questionId, submissionIdentity) { mutableStateOf(false) }
    val qid = question.questionId
    if (question !is IeltsCandidateCompletion) {
        val label = if (question is IeltsCandidateChoice) review.submittedAnswer else ieltsAnswerLabel(question, review.submittedAnswer)
        Text("你的作答：$label", Modifier.fillMaxWidth().testTag("ielts_your_answer_$qid"), style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ielts_review_toggle_$qid")) {
        Text(if (expanded) "收起参考与依据" else "查看参考与依据")
    }
    IeltsDisclosure(expanded) {
        Column(Modifier.fillMaxWidth().testTag("ielts_review_$qid"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("AI 参考：${ieltsAnswerLabel(question, review.referenceAnswer)}",
                Modifier.fillMaxWidth().testTag("ielts_reference_$qid"), style = MaterialTheme.typography.titleSmall)
            Text(review.explanation, Modifier.fillMaxWidth().testTag("ielts_explanation_$qid"), style = MaterialTheme.typography.bodyMedium)
            if (review.withinWordLimit == false) Text("本次填写 ${review.wordCount} 词，超过题目上限。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("ielts_word_limit_feedback_$qid"))
            if (review.numberRuleSatisfied == false) Text("本题不允许数字，作答已原样保留。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (review.sourceRefs.isNotEmpty()) {
                Text("原文依据", style = MaterialTheme.typography.labelLarge)
                review.sourceRefs.forEachIndexed { index, source ->
                    SelectionContainer { Text(source.quote, Modifier.fillMaxWidth().testTag("ielts_evidence_${qid}_$index"),
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = ReadingSerif,
                            lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.6f)) }
                }
            }
            review.reviewedParagraphIds?.let { ids ->
                val positions = ids.mapNotNull { id -> paragraphs.indexOfFirst { it.paragraphId == id }.takeIf { it >= 0 }?.plus(1) }
                Text("检查范围：第 ${positions.joinToString("、")} 段", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("ielts_ng_scope_$qid"))
            }
            review.absenceJustification?.let { rationale -> Text(rationale, Modifier.fillMaxWidth().testTag("ielts_ng_reason_$qid"),
                style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun IeltsWritingCard(task: IeltsCandidateWriting, text: String, words: Int?, editable: Boolean, reviewed: Boolean,
    onWriting: (String) -> Unit, inputGesture: IeltsInputGesture) {
    Surface(Modifier.fillMaxWidth().testTag("ielts_writing"), color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("写作 · Task 2", style = MaterialTheme.typography.titleMedium)
            Text(task.prompt, Modifier.fillMaxWidth().testTag("ielts_writing_prompt"), style = MaterialTheme.typography.bodyLarge)
            Text("至少 ${task.minimumWords} 词 · 建议 ${task.suggestedMinutes} 分钟", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = text, onValueChange = onWriting, enabled = editable || reviewed, readOnly = !editable,
                label = { Text(if (reviewed) "已提交作文" else "写下你的观点") }, minLines = 5, maxLines = 10,
                modifier = Modifier.fillMaxWidth().observeIeltsInputStart(inputGesture, true).testTag("ielts_writing_input"),
                shape = RoundedCornerShape(8.dp))
            Text(if (words == null) "字数暂不可用" else "$words 词 / ${task.minimumWords} 词要求",
                Modifier.fillMaxWidth().testTag("ielts_writing_count"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (reviewed && words != null && words < task.minimumWords) Text("篇幅不足已记录，不据此自动判零分。",
                Modifier.fillMaxWidth().testTag("ielts_short_writing"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun IeltsDisclosure(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(140)) + expandVertically(tween(180)),
        exit = fadeOut(tween(100)) + shrinkVertically(tween(160))) { content() }
}

private fun ieltsResponseComplete(question: IeltsCandidateQuestion, value: String?): Boolean = value != null && when (question) {
    is IeltsCandidateChoice -> value in question.options.map { it.optionId.name }
    is IeltsCandidateInformation -> value in question.responseValues.map { it.wire }
    is IeltsCandidateViewQuestion -> value in question.responseValues.map { it.wire }
    is IeltsCandidateCompletion -> value.isNotBlank() && value.length <= 200 && runCatching {
        IeltsExerciseValidator.validUnicode(value)
        value.none { it.code < 32 && it !in "\n\r\t" }
    }.getOrDefault(false)
}
private fun ieltsKind(question: IeltsCandidateQuestion): IeltsReadingType = when (question) {
    is IeltsCandidateChoice -> IeltsReadingType.MULTIPLE_CHOICE
    is IeltsCandidateInformation -> IeltsReadingType.IDENTIFYING_INFORMATION
    is IeltsCandidateViewQuestion -> IeltsReadingType.IDENTIFYING_WRITERS_VIEWS
    is IeltsCandidateCompletion -> IeltsReadingType.SHORT_TEXT_COMPLETION
}
private fun ieltsTypeLabel(question: IeltsCandidateQuestion): String = when (question) {
    is IeltsCandidateChoice -> "单项选择"
    is IeltsCandidateInformation -> "信息判断"
    is IeltsCandidateViewQuestion -> "作者观点判断"
    is IeltsCandidateCompletion -> "原文填空"
}
private fun ieltsInformationLabel(value: String) = when (value) {
    "true" -> "TRUE · 符合原文"; "false" -> "FALSE · 不符原文"; else -> "NOT GIVEN · 未提及"
}
private fun ieltsViewLabel(value: String) = when (value) {
    "yes" -> "YES · 符合作者观点"; "no" -> "NO · 不符作者观点"; else -> "NOT GIVEN · 未提及"
}
private fun ieltsAnswerLabel(question: IeltsCandidateQuestion, value: String): String = when (question) {
    is IeltsCandidateChoice -> question.options.firstOrNull { it.optionId.name == value }?.let { "$value. ${it.text}" } ?: value
    is IeltsCandidateInformation -> ieltsInformationLabel(value)
    is IeltsCandidateViewQuestion -> ieltsViewLabel(value)
    is IeltsCandidateCompletion -> value
}
