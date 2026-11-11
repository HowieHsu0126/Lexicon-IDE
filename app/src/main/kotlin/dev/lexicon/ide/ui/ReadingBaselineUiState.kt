package dev.lexicon.ide.ui

import dev.lexicon.domain.*

internal enum class BaselineStage { SETUP, READY, READING, QUESTION, RESULT }

/** No timer drives recomposition: time is sampled only at reading transitions. */
internal data class BaselineUiState(
    val stage: BaselineStage = BaselineStage.SETUP,
    val body: ArticleBody? = null,
    val plan: SpeedPlan? = null,
    val startParagraph: Int = 0,
    val paragraphCount: Int = 1,
    val userConfirmedUnseen: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val aiAllowed: Boolean = false,
    val hasKey: Boolean = false,
    val paragraphIndex: Int = 0,
    val playing: Boolean = false,
    val elapsedMillis: Long = 0,
    val selectedAnswer: Int? = null,
    val attempt: ReadingBaselineAttempt? = null,
    val summary: ReadingBaselineSummary? = null,
    val material: ReadingBaselineMaterial? = null,
    val canOpenOriginal: Boolean = true,
)

internal data class BaselineUiActions(
    val onBack: () -> Unit = {},
    val onReload: () -> Unit = {},
    val onStartParagraph: (Int) -> Unit = {},
    val onParagraphCount: (Int) -> Unit = {},
    val onConfirmUnseen: (Boolean) -> Unit = {},
    val onPrepare: (Boolean) -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onCompleteParagraph: () -> Unit = {},
    val onAnswer: (Int) -> Unit = {},
    val onSubmitAnswer: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onReadOriginal: () -> Unit = {},
)
