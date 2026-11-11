package dev.lexicon.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lexicon.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Generates synthetic import fixtures, never a user's learning data. */
@RunWith(AndroidJUnit4::class)
class AnkiCompatibilityFixtureTest {
    @Test fun writePackagesForRealAnkiImport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.cacheDir, "anki-compat-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val cards = (1..500).map { i -> Card("compat-$i", "capture-$i",
                VocabularyContent("fixtureword$i", "Synthetic baseline definition $i.",
                    listOf(Collocation("first fixture phrase", "A synthetic example."), Collocation("second fixture phrase", "Another synthetic example.")),
                    "A synthetic paraphrase", "formal", "medium"),
                "This is synthetic context $i.", SourceRef("Synthetic validation fixture", "Lexicon test"),
                topic = "Education", createdAt = 1793145600000, updatedAt = 1793145600000) }
            val changed = cards.mapIndexed { index, card -> if (index == 0) card.copy(
                content = card.content.copy(contextDefinition = "Synthetic UPDATED definition for compatibility validation."),
                updatedAt = card.updatedAt + 10_000,
            ) else card }
            for ((name, values) in listOf("lexicon-validation-baseline.apkg" to cards, "lexicon-validation-updated.apkg" to changed)) {
                val packageFile = LegacyAnkiExporter().export(values, File(folder, name))
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Lexicon-Validation")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }) ?: error("Cannot publish synthetic fixture")
                context.contentResolver.openOutputStream(uri)!!.use { output -> packageFile.inputStream().use { it.copyTo(output) } }
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
        } finally { folder.deleteRecursively() }
    }
}
