package dev.lexicon.ide.ui

import androidx.compose.runtime.saveable.SaverScope
import dev.lexicon.domain.ReadingEntryKind
import dev.lexicon.domain.StudyMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CardNavigationTest {
    private val scope = object : SaverScope { override fun canBeSaved(value: Any) = value is String }
    private val reader = CardNavigationSnapshot(
        route = "knowledge", cardId = "earlier-card", readerId = "article-α", readerPage = true,
        speedMode = true, speedParagraph = 7, readerEntry = 12, initialMode = StudyMode.SKIM,
        readerEntryKind = ReadingEntryKind.ANCHOR, readerBudgetMinutes = 10,
        readerAnchorHash = "body-hash", readerAnchorStart = 41, readerAnchorEnd = 73,
        readerScopeKey = "reader-original-α",
    )

    @Test fun saverPreservesNestedReturnOrderAndEveryReaderControl() {
        val stack = listOf(
            NavigationReturnPoint(NavigationReturnType.CARD, reader),
            NavigationReturnPoint(NavigationReturnType.SOURCE, CardNavigationSnapshot(route = "card", cardId = "card-b"), true),
        )
        val saved = with(CardNavigationStackSaver) { scope.save(stack) }
        val restored = requireNotNull(CardNavigationStackSaver.restore(requireNotNull(saved)))
        assertEquals(stack, restored)
        assertEquals("card-b", restored.last().snapshot.cardId)
        assertEquals(reader, restored.dropLast(1).last().snapshot)
        assertTrue(restored.last().externalFinish)
    }

    @Test fun nullableFieldsAndEmptyStackRoundTripWithoutSentinelIds() {
        val plain = NavigationReturnPoint(NavigationReturnType.CARD, CardNavigationSnapshot())
        assertEquals(listOf(plain), CardNavigationCodec.decode(CardNavigationCodec.encode(listOf(plain))))
        assertEquals(emptyList<NavigationReturnPoint>(), CardNavigationCodec.decode(CardNavigationCodec.encode(emptyList())))
    }

    @Test fun differentReaderScopesRemainDifferentEvenForTheSameArticle() {
        val stack = listOf(
            NavigationReturnPoint(NavigationReturnType.CARD, reader),
            NavigationReturnPoint(NavigationReturnType.SOURCE, reader.copy(readerScopeKey = "source-visit", readerEntry = 13)),
        )
        val restored = requireNotNull(CardNavigationCodec.decode(CardNavigationCodec.encode(stack)))
        assertEquals(reader.readerId, restored[1].snapshot.readerId)
        assertNotEquals(restored[0].snapshot.readerScopeKey, restored[1].snapshot.readerScopeKey)
        assertEquals(12, restored[0].snapshot.readerEntry)
        assertEquals(13, restored[1].snapshot.readerEntry)
    }

    @Test fun malformedIncompleteAndUnknownVersionStacksDoNotPartiallyRestore() {
        assertNull(CardNavigationCodec.decode("{broken"))
        assertNull(CardNavigationCodec.decode("[]"))
        val valid = Json.parseToJsonElement(CardNavigationCodec.encode(listOf(
            NavigationReturnPoint(NavigationReturnType.CARD, reader),
        ))).jsonObject
        assertNull(CardNavigationCodec.decode(JsonObject(valid + ("version" to JsonPrimitive(2))).toString()))
        val frame = valid.getValue("returns").jsonArray.single().jsonObject
        val invalidType = JsonObject(frame + ("type" to JsonPrimitive("UNKNOWN")))
        assertNull(CardNavigationCodec.decode(JsonObject(valid + ("returns" to JsonArray(listOf(frame, invalidType)))).toString()))
        val partial = JsonObject(frame - "snapshot")
        assertNull(CardNavigationCodec.decode(JsonObject(valid + ("returns" to JsonArray(listOf(partial)))).toString()))
    }

    @Test fun snapshotsAreSerializableWithoutCapturingUiOrRepositoryObjects() {
        val original = NavigationReturnPoint(NavigationReturnType.CARD, reader, true)
        val bytes = ByteArrayOutputStream().also { stream -> ObjectOutputStream(stream).use { it.writeObject(original) } }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
        assertEquals(original, restored)
    }
}
