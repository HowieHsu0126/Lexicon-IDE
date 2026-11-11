package dev.lexicon.ide.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lexicon.domain.ContextOrigin
import dev.lexicon.domain.Rating
import dev.lexicon.domain.isDueForReview
import dev.lexicon.domain.Card as LearningCard

@Composable
internal fun ReviewScreen(
    cards: List<LearningCard>, busy: Boolean,
    onReview: (LearningCard, Rating, () -> Unit) -> Unit, onRead: () -> Unit,
    onPauseCard: ((LearningCard) -> Unit)? = null, now: Long = System.currentTimeMillis(),
    onOpenSource: ((LearningCard) -> Unit)? = null, canOpenSource: (LearningCard) -> Boolean = { true },
    progress: ReviewProgressUiState? = null, onOpenCard: ((String) -> Unit)? = null,
    onRetryHistory: (() -> Unit)? = null,
) {
    var completed by rememberSaveable { mutableIntStateOf(0) }
    // A successful write can finish before either repository flow reaches this frame.
    // This guard hides only the already-applied repetition; logs own the daily count.
    var confirmedRepetitions by rememberSaveable(stateSaver = mapSaver<Map<String, Int>>(
        save = { it }, restore = { saved -> saved.mapValues { (_, value) -> value as Int } },
    )) { mutableStateOf(emptyMap()) }
    var pendingHistoryCardId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingHistoryReps by rememberSaveable { mutableIntStateOf(-1) }
    var pendingBaselineCount by rememberSaveable { mutableIntStateOf(-1) }
    // SaveableStateHolder restores values without checking rememberSaveable inputs.
    // Keep the owning window with the wait so re-entry cannot carry it into another day.
    var pendingHistoryStart by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingHistoryEnd by rememberSaveable { mutableStateOf<Long?>(null) }
    val currentHistoryRange by rememberUpdatedState(progress?.range)
    var submissionSerial by rememberSaveable { mutableIntStateOf(0) }
    var acknowledgedSerial by rememberSaveable { mutableIntStateOf(0) }
    val receipt = progress?.summary?.latestReceipt
    val pendingBelongsToWindow = progress?.range?.let {
        it.startInclusive == pendingHistoryStart && it.endExclusive == pendingHistoryEnd
    } == true
    val historyUpdated = pendingBelongsToWindow && (
        (receipt != null && receipt.cardId == pendingHistoryCardId && receipt.afterReps > pendingHistoryReps) ||
            (pendingBaselineCount >= 0 && progress?.summary?.count?.let { it > pendingBaselineCount } == true))
    val historyPending = progress != null && pendingHistoryCardId != null &&
        pendingBelongsToWindow && !historyUpdated
    val visibleReceipt = receipt.takeUnless { historyPending }
    val lastCard = visibleReceipt?.let { latest -> cards.firstOrNull { it.id == latest.cardId } }
    LaunchedEffect(historyUpdated, pendingHistoryCardId, progress?.range, pendingBelongsToWindow) {
        // An unknown window can resolve to the same day after restoration; do not discard it yet.
        if (pendingHistoryCardId != null && (historyUpdated || (progress?.range != null && !pendingBelongsToWindow))) {
            pendingHistoryCardId = null
            pendingHistoryReps = -1
            pendingBaselineCount = -1
            pendingHistoryStart = null
            pendingHistoryEnd = null
        }
    }
    val eligible = remember(cards, now, receipt, confirmedRepetitions, progress != null) {
        cards.filter { candidate ->
            candidate.isDueForReview(now) &&
                !(receipt != null && receipt.cardId == candidate.id && candidate.state.reps < receipt.afterReps) &&
                !(progress != null && confirmedRepetitions[candidate.id]?.let { candidate.state.reps <= it } == true)
        }.sortedBy { it.state.dueAt }
    }
    LaunchedEffect(cards) {
        confirmedRepetitions = confirmedRepetitions.filter { (id, repetitions) ->
            cards.firstOrNull { it.id == id }?.state?.reps?.let { it <= repetitions } ?: true
        }
    }
    val card = eligible.firstOrNull()
    val currentIdentity by rememberUpdatedState(card?.let { it.id to it.state.reps })
    var revealed by rememberSaveable { mutableStateOf(false) }
    var submitted by remember(card?.id, card?.state?.reps) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    var scrollCardId by rememberSaveable { mutableStateOf(card?.id) }
    var scrollCardReps by rememberSaveable { mutableIntStateOf(card?.state?.reps ?: -1) }
    LaunchedEffect(card?.id, card?.state?.reps) {
        // A restored repository can briefly be empty. Keep the saved owner until
        // a real card arrives; only a different card/repetition starts at the top.
        if (card != null) {
            if (scrollCardId != card.id || scrollCardReps != card.state.reps) {
                revealed = false
                scroll.scrollTo(0)
            }
            scrollCardId = card.id
            scrollCardReps = card.state.reps
        }
    }
    LaunchedEffect(busy, submitted) { if (!busy && submitted) submitted = false }
    if (card == null) {
        Column(Modifier.fillMaxSize().testTag("review_completion").verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("暂无到期卡片", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("review_completion_title"))
            ReviewProgressText(progress, completed, null, "review_completion_count", onRetryHistory, historyPending)
            if (visibleReceipt != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("刚复习", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(lastCard?.content?.headword ?: "刚复习的卡片", style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("review_last_headword"))
                Text(reviewRatingLabel(visibleReceipt.rating), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("review_last_rating"))
                if (onOpenCard != null) {
                    OutlinedButton(onClick = { onOpenCard(visibleReceipt.cardId) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("review_last_card")) {
                        Text("回看这张卡")
                    }
                }
                if (lastCard != null && onOpenSource != null && canOpenSource(lastCard)) {
                    TextButton(onClick = { onOpenSource(lastCard) }, enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("review_last_source")) {
                        Text("回原文")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRead, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("review_return_reading"),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 13.dp)) { Text("回到阅读") }
        }
        return
    }
    Column(Modifier.fillMaxSize().testTag("review_card_scroll").verticalScroll(scroll).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("复习", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ReviewProgressText(progress, completed, eligible.size, "review_counts", onRetryHistory, historyPending)
        }
        if (visibleReceipt != null && onOpenCard != null) {
            TextButton(onClick = { onOpenCard(visibleReceipt.cardId) }, enabled = !busy && !submitted,
                modifier = Modifier.testTag("review_last_card")) { Text("回看刚复习的卡片") }
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(card.content.headword, style = MaterialTheme.typography.displaySmall.copy(fontSize = 36.sp, lineHeight = 46.sp),
                    color = MaterialTheme.colorScheme.primary)
                FieldLabel(when (card.contextOrigin) {
                    ContextOrigin.USER_SUPPLIED -> "用户补充语境"
                    ContextOrigin.AI_GENERATED -> "AI 改写语境"
                    else -> "原文语境"
                })
                QuoteBlock(card.sourceSentence)
            }
        }
        if (onOpenSource != null && canOpenSource(card)) {
            TextButton(enabled = !busy && !submitted, onClick = { onOpenSource(card) }, modifier = Modifier.testTag("review_open_source")) {
                Text("回原文")
            }
        }
        onPauseCard?.let { pause ->
            TextButton(enabled = !busy && !submitted, onClick = { pause(card) }, modifier = Modifier.testTag("review_pause_card")) {
                Text("暂停这张卡")
            }
        }
        if (!revealed) {
            Button(onClick = { revealed = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("review_answer"),
                enabled = !busy) { Text("显示答案") }
        }
        AnimatedVisibility(revealed, enter = fadeIn(tween(200)) + expandVertically(tween(240)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(160))) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                AnalysisContent(card.content)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Rating.entries.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { rating ->
                            OutlinedButton(enabled = !busy && !submitted,
                                modifier = Modifier.weight(1f).heightIn(min = 50.dp).testTag("review_rate_${rating.name.lowercase()}"),
                                onClick = {
                                    if (!submitted) {
                                        submitted = true
                                        submissionSerial += 1
                                        val serial = submissionSerial
                                        val submittedRange = progress?.range
                                        val submittedBaselineCount = progress?.summary?.count
                                        var handled = false
                                        onReview(card, rating) {
                                            if (!handled) {
                                                handled = true
                                                if (progress == null) completed += 1
                                                else {
                                                    confirmedRepetitions = confirmedRepetitions + (card.id to card.state.reps)
                                                    if (serial > acknowledgedSerial) {
                                                        acknowledgedSerial = serial
                                                        if (currentHistoryRange == submittedRange) {
                                                            // A conflated history update may already name another
                                                            // card. Any newer committed count ends this brief wait.
                                                            pendingHistoryCardId = card.id.takeIf { submittedBaselineCount != null && submittedRange != null }
                                                            pendingHistoryReps = card.state.reps
                                                            pendingBaselineCount = submittedBaselineCount ?: -1
                                                            pendingHistoryStart = submittedRange?.startInclusive
                                                            pendingHistoryEnd = submittedRange?.endExclusive
                                                        }
                                                    }
                                                }
                                                if (currentIdentity == (card.id to card.state.reps)) revealed = false
                                                submitted = false
                                            }
                                        }
                                    }
                                }, colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (rating == Rating.GOOD) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    contentColor = if (rating == Rating.GOOD) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)) {
                                Text(reviewRatingLabel(rating))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ReviewProgressText(
    progress: ReviewProgressUiState?, localCompleted: Int, due: Int?, countTag: String, onRetry: (() -> Unit)?,
    refreshing: Boolean = false,
) {
    val count = if (progress == null) {
        if (due == null) "本轮完成 $localCompleted 次复习。" else "$localCompleted 完成 · $due 待复习"
    } else progress.summary?.takeUnless { refreshing }?.let { summary ->
        "今日完成 ${summary.count} 次复习" + if (due == null) "" else " · $due 待复习"
    }
    if (count != null) Text(count, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(countTag))
    if (progress != null && (progress.failed || progress.loading || progress.summary == null || refreshing)) {
        Text(when {
            progress.failed -> "复习记录暂不可用"
            refreshing -> "正在更新复习记录…"
            else -> "正在读取复习记录…"
        }, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("review_history_status"))
        if (progress.failed && onRetry != null) TextButton(onClick = onRetry, modifier = Modifier.testTag("review_history_retry")) {
            Text("重试")
        }
    }
}

private fun reviewRatingLabel(rating: Rating): String = when (rating) {
    Rating.AGAIN -> "Again · 忘记"
    Rating.HARD -> "Hard · 困难"
    Rating.GOOD -> "Good · 记得"
    Rating.EASY -> "Easy · 轻松"
}
