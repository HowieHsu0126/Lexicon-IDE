package dev.lexicon.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic cards only: proving preparation is not confused with successful file delivery. */
@RunWith(AndroidJUnit4::class)
class ExportDeliveryTest {
    @Test fun onlyConfirmedDeliveredSnapshotMarksMatchingContentVersion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = LexiconRepository(context)
        repo.initialize()
        val id = UUID.randomUUID().toString()
        val capture = Capture(id, target = "evidence", selectedText = "evidence", sentence = "The study provides evidence.", createdAt = 1000L)
        val content = VocabularyContent("evidence", "information supporting a claim", listOf(
            Collocation("provide evidence", "The study provides evidence."),
            Collocation("assess evidence", "Researchers assess evidence.")), "supporting information", "formal", "medium")
        val card = repo.saveCard(capture, AnalysisResult(content, "synthetic-test"))
        val temp = File(context.cacheDir, "delivery-$id.apkg")
        repo.exportAnki(temp)
        assertNull(repo.cards.first().first { it.id == card.id }.ankiExportedAt)
        repo.confirmAnkiExport(temp)
        assertNotNull(repo.cards.first().first { it.id == card.id }.ankiExportedAt)

        repo.exportAnki(temp)
        val changed = repo.cards.first().first { it.id == card.id }.copy(content = content.copy(paraphrase = "additional supporting facts"))
        repo.updateCard(changed)
        val beforeConfirmation = repo.cards.first().first { it.id == card.id }
        repo.confirmAnkiExport(temp)
        val afterConfirmation = repo.cards.first().first { it.id == card.id }
        assertEquals(beforeConfirmation.ankiExportedAt, afterConfirmation.ankiExportedAt)
        assertEquals("additional supporting facts", afterConfirmation.content.paraphrase)
        temp.delete()
        Unit
    }
}
