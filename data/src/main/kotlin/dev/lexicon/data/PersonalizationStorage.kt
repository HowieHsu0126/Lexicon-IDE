package dev.lexicon.data

import dev.lexicon.domain.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/** One strict validator serves reads, writes, health, and recovery; no read/write disagreement. */
internal object PersonalizationStorage {
    const val PROFILE_KEY = "personalization:profile"
    const val BACKUP_PREFIX = "personalization:backup:"
    private const val MAX_CHARACTERS = 2_000_000

    fun text(bytes: ByteArray): String {
        require(bytes.size <= MAX_CHARACTERS * 4) { "个性化记录超过本机大小限制" }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            .also { require(it.length <= MAX_CHARACTERS) { "个性化记录超过本机大小限制" } }
    }

    fun decodeRaw(bytes: ByteArray?): PersonalizationProfile = decode(bytes?.let(::text))

    fun decode(data: String?): PersonalizationProfile {
        if (data == null) return PersonalizationProfile()
        require(data.length <= MAX_CHARACTERS) { "个性化记录超过本机大小限制" }
        return Json.decodeFromString<PersonalizationProfile>(data).also(::validate)
    }

    fun validate(profile: PersonalizationProfile) {
        require(profile.revision >= 0 && profile.rates.values.all { it in 30..180 })
        require(profile.holds.size <= 1024 && profile.holds.map { Triple(it.granularity, it.rate, it.direction) }.distinct().size == profile.holds.size)
        require(profile.holds.all { it.rate in 30..180 && it.direction in listOf(-1, 1) && it.createdAt >= 0 })
        require(profile.decisions.size <= 1024) { "个性化决定已达到本机数量限制，原记录保持不变" }
        require(profile.decisions.map { it.id }.distinct().size == profile.decisions.size)
        val digest = Regex("[a-f0-9]{64}")
        profile.decisions.forEach { decision ->
            val suggestion = decision.suggestion
            require(decision.id == suggestion.id && decision.id.startsWith("pace:") && digest.matches(decision.id.removePrefix("pace:")))
            require(decision.decidedAt >= 0 && decision.updatedAt >= decision.decidedAt)
            require(suggestion.fromRate in 30..180 && suggestion.toRate in 30..180 && suggestion.fromRate != suggestion.toRate)
            require(suggestion.sampleCount in 2..PersonalizationPolicy.MAX_EVIDENCE_RUNS && suggestion.evidenceRunIds.size == suggestion.sampleCount)
            require(suggestion.evidenceRunIds.distinct().size == suggestion.sampleCount && suggestion.evidenceRunIds.all {
                it.isNotBlank() && it.length <= 200 && it.none { character -> character.isISOControl() }
            })
            require(suggestion.total in PersonalizationPolicy.MIN_QUESTIONS..suggestion.sampleCount * SpeedChunker.MAX_UNIT_PARAGRAPHS &&
                suggestion.correct in 0..suggestion.total && digest.matches(suggestion.evidenceHash))
            require(Regex("[a-zA-Z0-9_-]{1,40}").matches(suggestion.ruleVersion))
            if (decision.status == PersonalizationDecisionStatus.DISMISSED) require(decision.appliedRevision == null)
            else require(decision.appliedRevision != null && decision.appliedRevision!! in 1..profile.revision)
        }
        profile.activePaceBinding?.let { binding ->
            val decision = requireNotNull(profile.decisions.find { it.id == binding.decisionId })
            require(binding.profileRevision in 1..profile.revision && binding.profileRevision == decision.appliedRevision &&
                decision.status == PersonalizationDecisionStatus.APPLIED && binding.granularity == decision.suggestion.granularity &&
                binding.rate == decision.suggestion.toRate && profile.granularity == binding.granularity && profile.rateFor() == binding.rate)
        }
        profile.undoPaceBindings.forEach { (granularity, binding) ->
            val decision = requireNotNull(profile.decisions.find { it.id == binding.decisionId })
            require(binding.granularity == granularity && binding.profileRevision in 1..profile.revision &&
                binding.profileRevision == decision.appliedRevision && decision.status == PersonalizationDecisionStatus.APPLIED &&
                decision.suggestion.granularity == granularity && binding.rate == decision.suggestion.toRate &&
                profile.rateFor(granularity) == binding.rate)
        }
    }

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun health(bytes: ByteArray?): PersonalizationHealth {
        if (bytes == null) return PersonalizationHealth(PersonalizationHealthStatus.MISSING)
        return PersonalizationHealth(
            if (runCatching { decodeRaw(bytes) }.isSuccess) PersonalizationHealthStatus.OK else PersonalizationHealthStatus.CORRUPT,
            digest(bytes),
        )
    }
}
