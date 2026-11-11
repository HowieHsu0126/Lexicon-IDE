package dev.lexicon.domain

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * FSRS-6, ported from open-spaced-repetition/py-fsrs v6.3.2 (MIT).
 * See domain/reference/NOTICE.md for source hashes and reproducible fixtures.
 * Times are UTC epoch milliseconds. Like the frozen reference, recall uses elapsed whole days.
 */
class Fsrs6Scheduler(
    val desiredRetention: Double = 0.9,
    val maximumIntervalDays: Int = 36_500,
) : Scheduler {
    init {
        require(desiredRetention.isFinite() && desiredRetention > 0 && desiredRetention < 1)
        require(maximumIntervalDays > 0)
    }

    private val p = DEFAULT_PARAMETERS
    private val decay = -p[20]
    private val factor = 0.9.pow(1.0 / decay) - 1.0

    override fun retrievability(state: FsrsState, now: Long): Double? {
        val last = state.lastReviewAt ?: return null
        val stability = state.stability ?: return null
        require(stability.isFinite() && stability >= STABILITY_MIN)
        val elapsed = max(0, Math.floorDiv(now - last, DAY_MS))
        return (1.0 + factor * elapsed / stability).pow(decay)
    }

    override fun review(state: FsrsState, rating: Rating, now: Long): FsrsState {
        validateState(state)
        require(state.lastReviewAt == null || now >= state.lastReviewAt) {
            "复习时间早于上次记录，请检查设备时间"
        }
        val grade = rating.ordinal + 1
        var phase = if (state.phase == CardPhase.NEW) CardPhase.LEARNING else state.phase
        var step = if (state.phase == CardPhase.NEW) 0 else state.step
        val oldStability = state.stability
        val oldDifficulty = state.difficulty
        val elapsedDays = state.lastReviewAt?.let { Math.floorDiv(now - it, DAY_MS) }

        val stability: Double
        val difficulty: Double
        if (oldStability == null || oldDifficulty == null) {
            stability = p[grade - 1].coerceAtLeast(STABILITY_MIN)
            difficulty = initialDifficulty(grade).coerceIn(1.0, 10.0)
        } else {
            stability = if (elapsedDays != null && elapsedDays < 1) {
                shortTermStability(oldStability, grade)
            } else {
                nextStability(oldStability, oldDifficulty, retrievability(state, now) ?: 0.0, grade)
            }
            difficulty = nextDifficulty(oldDifficulty, grade)
        }

        val intervalMs: Long
        if (phase == CardPhase.REVIEW) {
            if (rating == Rating.AGAIN) {
                phase = CardPhase.RELEARNING
                step = 0
                intervalMs = RELEARNING_STEPS[0]
            } else {
                intervalMs = nextInterval(stability) * DAY_MS
            }
        } else {
            val steps = if (phase == CardPhase.LEARNING) LEARNING_STEPS else RELEARNING_STEPS
            val currentStep = requireNotNull(step)
            if (currentStep >= steps.size && rating != Rating.AGAIN) {
                phase = CardPhase.REVIEW
                step = null
                intervalMs = nextInterval(stability) * DAY_MS
            } else {
                when (rating) {
                    Rating.AGAIN -> {
                        step = 0
                        intervalMs = steps[0]
                    }
                    Rating.HARD -> {
                        intervalMs = when {
                            currentStep == 0 && steps.size == 1 -> steps[0] * 3 / 2
                            currentStep == 0 -> (steps[0] + steps[1]) / 2
                            else -> steps[currentStep]
                        }
                    }
                    Rating.GOOD -> {
                        if (currentStep + 1 == steps.size) {
                            phase = CardPhase.REVIEW
                            step = null
                            intervalMs = nextInterval(stability) * DAY_MS
                        } else {
                            step = currentStep + 1
                            intervalMs = steps[step]
                        }
                    }
                    Rating.EASY -> {
                        phase = CardPhase.REVIEW
                        step = null
                        intervalMs = nextInterval(stability) * DAY_MS
                    }
                }
            }
        }
        return FsrsState(
            phase = phase, stability = stability, difficulty = difficulty,
            dueAt = Math.addExact(now, intervalMs), lastReviewAt = now, step = step,
            reps = Math.addExact(state.reps, 1),
            lapses = if (state.phase == CardPhase.REVIEW && rating == Rating.AGAIN)
                Math.addExact(state.lapses, 1) else state.lapses,
        )
    }

    private fun initialDifficulty(grade: Int): Double = p[4] - exp(p[5] * (grade - 1)) + 1

    private fun nextDifficulty(difficulty: Double, grade: Int): Double {
        val delta = -p[6] * (grade - 3)
        val damped = difficulty + (10 - difficulty) * delta / 9
        return (p[7] * initialDifficulty(4) + (1 - p[7]) * damped).coerceIn(1.0, 10.0)
    }

    private fun shortTermStability(stability: Double, grade: Int): Double {
        var increase = exp(p[17] * (grade - 3 + p[18])) * stability.pow(-p[19])
        // v6.3.2: Hard must not reduce same-day stability, as with Good/Easy.
        if (grade >= 2) increase = max(increase, 1.0)
        return max(stability * increase, STABILITY_MIN)
    }

    private fun nextStability(stability: Double, difficulty: Double, r: Double, grade: Int): Double {
        val next = if (grade == 1) {
            min(
                p[11] * difficulty.pow(-p[12]) * ((stability + 1).pow(p[13]) - 1) * exp((1 - r) * p[14]),
                stability / exp(p[17] * p[18]),
            )
        } else {
            val hardPenalty = if (grade == 2) p[15] else 1.0
            val easyBonus = if (grade == 4) p[16] else 1.0
            stability * (1 + exp(p[8]) * (11 - difficulty) * stability.pow(-p[9]) *
                (exp((1 - r) * p[10]) - 1) * hardPenalty * easyBonus)
        }
        return max(next, STABILITY_MIN)
    }

    private fun nextInterval(stability: Double): Long {
        val days = (stability / factor) * (desiredRetention.pow(1.0 / decay) - 1)
        // Python round uses ties-to-even; roundToLong would introduce a subtle mismatch.
        return Math.rint(days).toLong().coerceIn(1, maximumIntervalDays.toLong())
    }

    private fun validateState(state: FsrsState) {
        require(state.reps >= 0 && state.lapses >= 0 && state.lapses <= state.reps)
        if (state.phase == CardPhase.NEW) {
            require(state.stability == null && state.difficulty == null && state.lastReviewAt == null)
        } else {
            require(state.stability != null && state.stability.isFinite() && state.stability >= STABILITY_MIN)
            require(state.difficulty != null && state.difficulty.isFinite() && state.difficulty in 1.0..10.0)
            require(state.lastReviewAt != null)
            if (state.phase == CardPhase.REVIEW) require(state.step == null)
            else require(state.step != null && state.step >= 0)
        }
    }

    companion object {
        const val VERSION = "FSRS-6/py-fsrs-6.3.2"
        const val DAY_MS = 86_400_000L
        private const val STABILITY_MIN = 0.001
        private val LEARNING_STEPS = longArrayOf(60_000L, 600_000L)
        private val RELEARNING_STEPS = longArrayOf(600_000L)
        val DEFAULT_PARAMETERS: List<Double> = listOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194,
            0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629,
            1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
        )
    }
}
