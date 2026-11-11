package dev.lexicon.ide.ui

import androidx.compose.runtime.saveable.Saver
import dev.lexicon.domain.ReadingEntryKind
import dev.lexicon.domain.StudyMode
import java.io.Serializable
import kotlinx.serialization.json.*

internal enum class NavigationReturnType { CARD, SOURCE }

/** Only the state needed to return from a card or its temporary original-article visit. */
internal data class CardNavigationSnapshot(
    val route: String = "articles",
    val cardId: String? = null,
    val readerId: String? = null,
    val readerPage: Boolean = false,
    val speedMode: Boolean = false,
    val speedParagraph: Int = 0,
    val readerEntry: Int = 0,
    val initialMode: StudyMode = StudyMode.INTENSIVE,
    val readerEntryKind: ReadingEntryKind = ReadingEntryKind.START,
    val readerBudgetMinutes: Int? = null,
    val readerAnchorHash: String? = null,
    val readerAnchorStart: Int = 0,
    val readerAnchorEnd: Int = 0,
    val readerScopeKey: String? = null,
) : Serializable

internal data class NavigationReturnPoint(
    val type: NavigationReturnType,
    val snapshot: CardNavigationSnapshot,
    val externalFinish: Boolean = false,
) : Serializable

/** A string payload is supported by saved-instance state without an app serialization plugin. */
internal val CardNavigationStackSaver: Saver<List<NavigationReturnPoint>, String> = Saver(
    save = { CardNavigationCodec.encode(it) },
    restore = { CardNavigationCodec.decode(it) },
)

internal object CardNavigationCodec {
    fun encode(stack: List<NavigationReturnPoint>): String = buildJsonObject {
        put("version", 1)
        putJsonArray("returns") {
            stack.forEach { point -> add(buildJsonObject {
                put("type", point.type.name)
                put("externalFinish", point.externalFinish)
                putJsonObject("snapshot") {
                    val value = point.snapshot
                    put("route", value.route); put("cardId", value.cardId)
                    put("readerId", value.readerId); put("readerPage", value.readerPage)
                    put("speedMode", value.speedMode); put("speedParagraph", value.speedParagraph)
                    put("readerEntry", value.readerEntry); put("initialMode", value.initialMode.name)
                    put("readerEntryKind", value.readerEntryKind.name); put("readerBudgetMinutes", value.readerBudgetMinutes)
                    put("readerAnchorHash", value.readerAnchorHash); put("readerAnchorStart", value.readerAnchorStart)
                    put("readerAnchorEnd", value.readerAnchorEnd); put("readerScopeKey", value.readerScopeKey)
                }
            }) }
        }
    }.toString()

    /** An incompatible or incomplete return stack is discarded as a unit. */
    fun decode(encoded: String): List<NavigationReturnPoint>? = runCatching {
        val root = Json.parseToJsonElement(encoded).jsonObject
        require(root.getValue("version").jsonPrimitive.int == 1)
        root.getValue("returns").jsonArray.map { element ->
            val point = element.jsonObject
            val value = point.getValue("snapshot").jsonObject
            NavigationReturnPoint(
                type = NavigationReturnType.valueOf(point.string("type")),
                snapshot = CardNavigationSnapshot(
                    route = value.string("route").also { require(it.isNotBlank()) },
                    cardId = value.nullableString("cardId"), readerId = value.nullableString("readerId"),
                    readerPage = value.getValue("readerPage").jsonPrimitive.boolean,
                    speedMode = value.getValue("speedMode").jsonPrimitive.boolean,
                    speedParagraph = value.getValue("speedParagraph").jsonPrimitive.int,
                    readerEntry = value.getValue("readerEntry").jsonPrimitive.int,
                    initialMode = StudyMode.valueOf(value.string("initialMode")),
                    readerEntryKind = ReadingEntryKind.valueOf(value.string("readerEntryKind")),
                    readerBudgetMinutes = value.getValue("readerBudgetMinutes").takeUnless { it == JsonNull }?.jsonPrimitive?.int,
                    readerAnchorHash = value.nullableString("readerAnchorHash"),
                    readerAnchorStart = value.getValue("readerAnchorStart").jsonPrimitive.int,
                    readerAnchorEnd = value.getValue("readerAnchorEnd").jsonPrimitive.int,
                    readerScopeKey = value.nullableString("readerScopeKey"),
                ),
                externalFinish = point.getValue("externalFinish").jsonPrimitive.boolean,
            )
        }
    }.getOrNull()

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let {
        require(it.isString); it.content
    }

    private fun JsonObject.nullableString(key: String): String? =
        getValue(key).takeUnless { it == JsonNull }?.jsonPrimitive?.let { require(it.isString); it.content }
}
