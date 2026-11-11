package dev.lexicon.ide.ui

import androidx.compose.runtime.saveable.Saver
import dev.lexicon.domain.Capture
import dev.lexicon.domain.CaptureLimits
import kotlinx.serialization.json.Json

/** Active lookup material survives instance-state recreation without becoming a saved capture. */
internal val CaptureDraftSaver: Saver<Capture?, String> = Saver(
    save = { it?.let(CaptureDraftCodec::encode) ?: "" },
    restore = CaptureDraftCodec::decode,
)

internal object CaptureDraftCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun encode(capture: Capture): String = json.encodeToString(Capture.serializer(), capture)

    fun decode(value: String): Capture? = runCatching {
        require(value.length in 1..262_144)
        json.decodeFromString(Capture.serializer(), value).also {
            require(it.id.isNotBlank() && it.id.length <= 256)
            require(it.target.length <= CaptureLimits.TARGET && it.selectedText.length <= CaptureLimits.SELECTED_TEXT &&
                it.sentence.length <= CaptureLimits.SENTENCE)
        }
    }.getOrNull()
}
