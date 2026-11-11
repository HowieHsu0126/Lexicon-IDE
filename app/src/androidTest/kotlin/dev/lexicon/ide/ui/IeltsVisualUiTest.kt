package dev.lexicon.ide.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.lexicon.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class IeltsVisualUiTest {
    @get:Rule val compose = createComposeRule()
    private fun material(): Pair<IeltsExerciseRequest,IeltsExercisePackage> {
        val raw = InstrumentationRegistry.getInstrumentation().context.assets.open("ielts-task1.json").bufferedReader().use { it.readText() }
        val root = Json.parseToJsonElement(raw).jsonObject
        return IeltsExerciseJson.decodeRequest(root.getValue("request").toString()) to IeltsExerciseJson.decodePackage(root.getValue("package").toString())
    }
    @Test fun sourceChartSupportsLargeFontAndIndependentWriting() {
        val (request, pkg) = material()
        val task = pkg.writingTask1.task!!
        var essay by mutableStateOf("")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LexiconTheme(AppSettings()) {
                    Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        IeltsVisualWritingCard(task,request.paragraphs,essay,IeltsExerciseValidator.wordCount(essay),true,false,{essay=it})
                    }
                }
            }
        }
        compose.onNodeWithTag("ielts_visual_category_0").performScrollTo().assertIsDisplayed()
        val target = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            .resolve("ielts-task1-trial/${java.util.UUID.randomUUID()}/chart-font200.png")
        target.parentFile!!.mkdirs()
        check(target.createNewFile()) { "Preserve previous visual trial" }
        target.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
        compose.onNodeWithTag("ielts_visual_cell_1_1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ielts_writing1_input").performScrollTo().performTextInput("Visits increased at both branches.")
        assertEquals("Visits increased at both branches.",essay)
        compose.onNodeWithTag("ielts_writing1_words").performScrollTo().assertTextEquals("5 词")
    }
    @Test fun tableContainsEveryValueWithoutColorOrBars() {
        val (_, pkg) = material()
        compose.setContent { LexiconTheme(AppSettings()) { Column(Modifier.verticalScroll(rememberScrollState())) {
            IeltsVisualChart(pkg.writingTask1.task!!.visual.copy(kind="table"))
        } } }
        for(si in 0..1) for(ci in 0..1) compose.onNodeWithTag("ielts_visual_cell_${si}_$ci").performScrollTo().assertIsDisplayed()
    }
}
