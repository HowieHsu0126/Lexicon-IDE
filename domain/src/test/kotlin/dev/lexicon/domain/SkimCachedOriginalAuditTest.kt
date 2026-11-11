package dev.lexicon.domain

import java.io.File
import kotlinx.serialization.json.*
import kotlinx.serialization.decodeFromString
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.*

/** Opt-in observation of supplied local original snapshots; no network, no copying full text into reports. */
class SkimCachedOriginalAuditTest {
    @Test fun inspectSelectedDisclosureAndConclusionRanges() {
        val input = System.getenv("LEXICON_SKIM_AUDIT_INPUT")
        val output = System.getenv("LEXICON_SKIM_AUDIT_OUTPUT")
        assumeTrue("Requires explicit local audit material and a fresh output", input != null && output != null)
        val file = File(output!!)
        require(!file.exists())
        val codec = Json { ignoreUnknownKeys = true }
        val observations = codec.parseToJsonElement(File(input!!).readText()).jsonArray.flatMap { item ->
            val row = item.jsonObject
            val article = codec.decodeFromJsonElement<Article>(row.getValue("article"))
            val body = codec.decodeFromJsonElement<ArticleBody>(row.getValue("body"))
            val disclosure = row.getValue("expectedDisclosureParagraph").jsonPrimitive.int
            val conclusion = row.getValue("expectedConclusionParagraph").jsonPrimitive.int
            val paragraphs = ParagraphFocus.paragraphs(body.text)
            assertTrue(disclosure in paragraphs.indices && conclusion in paragraphs.indices)
            listOf(5, 10).map { minutes ->
                val plan = ReadingSkimPlanner.plan(body.text, minutes, original = SkimOriginal(article, body))
                buildJsonObject {
                    put("articleId", article.id); put("bodyHash", body.contentHash)
                    put("minutes", minutes); put("planningWpm", 160); put("budgetWords", plan.wordBudget)
                    put("selectedWords", plan.wordsSelected); put("selectedParagraphs", JsonArray(plan.paragraphIndices.map(::JsonPrimitive)))
                    put("expectedDisclosureParagraph", disclosure); put("expectedConclusionParagraph", conclusion)
                    put("disclosureSelectedWords", plan.sentenceRanges.filter { it.paragraphIndex == disclosure }.sumOf { it.wordCount })
                    put("conclusionSelectedWords", plan.sentenceRanges.filter { it.paragraphIndex == conclusion }.sumOf { it.wordCount })
                    put("exactRanges", plan.sentenceRanges.all { range ->
                        ReadingSkimPlanner.sentences(body.text).any { it.start == range.start && it.endExclusive == range.endExclusive && it.paragraphIndex == range.paragraphIndex }
                    })
                }
            }
        }
        file.parentFile.mkdirs()
        file.writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(observations)))
        assertEquals(6, observations.size)
    }
}
