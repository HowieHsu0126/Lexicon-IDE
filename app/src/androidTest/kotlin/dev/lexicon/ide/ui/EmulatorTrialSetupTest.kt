package dev.lexicon.ide.ui

import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.ide.LexiconApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Local emulator-only opt-in. A key enters over a private stdin pipe, never a test argument. */
class EmulatorTrialSetupTest {
    @Test fun provisionPrivateKeyForInteractiveTrial() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lexiconTrial") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LexiconApplication
        val input = File(app.filesDir, "trial-key-input")
        check(input.isFile)
        try { app.repository.setApiKey(input.readText().trim()); check(app.repository.hasApiKey()) }
        finally { input.delete() }
    }
}
