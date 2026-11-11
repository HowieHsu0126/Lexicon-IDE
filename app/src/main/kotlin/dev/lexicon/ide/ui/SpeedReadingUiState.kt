package dev.lexicon.ide.ui

import dev.lexicon.domain.SpeedAnswer
import dev.lexicon.domain.SpeedGranularity
import dev.lexicon.domain.SpeedPlan
import dev.lexicon.domain.SpeedRun
import dev.lexicon.domain.PersonalizationProfile
import dev.lexicon.domain.PaceSuggestion
import dev.lexicon.domain.PersonalizationDecision
import dev.lexicon.domain.SpeedSegmentation
import dev.lexicon.domain.SpeedPacedRound

/** Passive presentation contract; timing, persistence and scoring belong to the controller. */
internal enum class SpeedStage { SETUP, READING, QUESTION, RESULT }

internal data class SpeedUiState(
    val stage: SpeedStage = SpeedStage.SETUP,
    val plan: SpeedPlan? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val selectedGranularity: SpeedGranularity = SpeedGranularity.L2,
    val startParagraph: Int = 0,
    val paragraphCount: Int = 1,
    val groupsPerMinute: Int = 60,
    val chunkIndex: Int = 0,
    val playing: Boolean = false,
    val sentenceOnly: Boolean = false,
    val answers: List<SpeedAnswer> = emptyList(),
    val selectedAnswer: Int? = null,
    val answerRevealed: Boolean = false,
    val aiAllowed: Boolean = true,
    val hasKey: Boolean = false,
    val result: SpeedRun? = null,
    val saving: Boolean = false,
    val feedbackSaved: Boolean = false,
    val profile: PersonalizationProfile = PersonalizationProfile(enabled = false),
    val suggestion: PaceSuggestion? = null,
    val paceAssessment: dev.lexicon.domain.PaceAssessment? = null,
    val paceEvidenceRuns: List<SpeedRun> = emptyList(),
    val lastApplied: PersonalizationDecision? = null,
    val personalizationBusy: Boolean = false,
    val personalizationError: String? = null,
    val personalizationAvailable: Boolean = false,
    val rememberTrainingChoice: Boolean? = null, // Derived from the current SETUP draft, never persisted separately.
    val canContinueParagraph: Boolean = false,
    val segmentation: SpeedSegmentation? = null,
    val segmentationBusy: Boolean = false,
    val segmentationError: String? = null,
    val segmentationMessage: String? = null,
    val round: SpeedPacedRound = SpeedPacedRound.GUIDED,
    val canContinueRound: Boolean = false,
    val natural: NaturalUiState? = null,
    val paceOutcome: dev.lexicon.domain.PersonalizationOutcome? = null,
    val paceOutcomeDecision: PersonalizationDecision? = null,
    val profileHealth: dev.lexicon.domain.PersonalizationHealth? = null,
    val recoveryReceipt: dev.lexicon.domain.PersonalizationRecoveryReceipt? = null,
    val recoveryError: String? = null,
)

internal data class SpeedUiActions(
    val onBack: () -> Unit = {},
    val onPrepare: (Boolean) -> Unit = {},
    val onGranularity: (SpeedGranularity) -> Unit = {},
    val onStartParagraph: (Int) -> Unit = {},
    val onParagraphCount: (Int) -> Unit = {},
    val onSpeed: (Int) -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onRepeat: () -> Unit = {},
    val onSeek: (Int) -> Unit = {},
    val onSentenceOnly: (Boolean) -> Unit = {},
    val onAnswer: (Int) -> Unit = {},
    val onContinue: () -> Unit = {},
    val onRestart: () -> Unit = {},
    val onCorrection: (String, String) -> Unit = { _, _ -> },
    val onRememberTraining: (Boolean) -> Unit = {},
    val onApplySuggestion: (String) -> Unit = {},
    val onDismissSuggestion: (String) -> Unit = {},
    val onUndoSuggestion: (String) -> Unit = {},
    val onNextParagraph: () -> Unit = {},
    val onEditSegmentation: () -> Unit = {},
    val onSaveSegmentation: (List<Int>) -> Unit = {},
    val onRestoreSegmentation: () -> Unit = {},
    val onCloseSegmentation: () -> Unit = {},
    val onReleasePaceHold: () -> Unit = {},
    val onNextRound: () -> Unit = {},
    val onNaturalPlayPause: () -> Unit = {},
    val onNaturalCompleteParagraph: () -> Unit = {},
    val onNaturalRetry: () -> Unit = {},
    val onReadOriginal: () -> Unit = {},
    val onRecoverProfile: () -> Unit = {},
    val onInspectRun: ((SpeedRun) -> Unit)? = null,
    val onPaceHistory: (() -> Unit)? = null,
    val onRetrySetupSettings: () -> Unit = {},
    val onPauseForTools: () -> Unit = {},
)
