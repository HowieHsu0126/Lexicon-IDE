package dev.lexicon.data

import dev.lexicon.domain.ReadingMode
import dev.lexicon.domain.Topics
import org.junit.Assert.*
import org.junit.Test

class SourceCatalogTest {
    @Test fun catalogHasEighteenUniqueOfficialFeedsAndKeepsOriginalIds() {
        val sources = SourceCatalog.sources
        assertEquals(18, sources.size)
        assertEquals(sources.size, sources.map { it.id }.distinct().size)
        assertEquals(sources.size, sources.map { it.feedUrl }.distinct().size)
        assertTrue(sources.all { it.feedUrl.startsWith("https://") })
        assertTrue(sources.map { it.id }.containsAll(listOf("conversation-us", "bbc-science", "guardian-science")))
        assertEquals(4, sources.count { it.id.startsWith("economist-") })
        assertTrue(sources.all { it.defaultTopic == null || Topics.children.containsKey(it.defaultTopic) })
    }

    @Test fun onlyConversationStartsSubscribedAndSupportsNativeReadingOrAi() {
        val sources = SourceCatalog.sources
        assertEquals(listOf("conversation-us"), sources.filter { it.enabled }.map { it.id })
        assertEquals(listOf("conversation-us"), sources.filter { it.readingMode == ReadingMode.NATIVE }.map { it.id })
        assertEquals(listOf("conversation-us"), sources.filter { it.aiAllowed }.map { it.id })
        assertTrue(sources.filter { it.id != "conversation-us" }.all { it.readingMode == ReadingMode.EXTERNAL })
    }

    @Test fun refreshChangesMetadataButPreservesSubscriptionAndHttpState() {
        val catalog = SourceCatalog.sources.single { it.id == "bbc-science" }
        val saved = catalog.copy(name = "Old name", license = "Old notice", aiAllowed = true,
            readingMode = ReadingMode.NATIVE, enabled = true, etag = "v1", lastModified = "Wed, 28 Oct 2026 10:00:00 GMT",
            lastFetchedAt = 123L, lastError = "Previous failure")
        val refreshed = SourceCatalog.refresh(catalog, saved)
        assertEquals(catalog.name, refreshed.name)
        assertEquals(catalog.license, refreshed.license)
        assertEquals(catalog.readingMode, refreshed.readingMode)
        assertFalse(refreshed.aiAllowed)
        assertTrue(refreshed.enabled)
        assertEquals(saved.etag, refreshed.etag)
        assertEquals(saved.lastModified, refreshed.lastModified)
        assertEquals(saved.lastFetchedAt, refreshed.lastFetchedAt)
        assertEquals(saved.lastError, refreshed.lastError)
    }

    @Test fun changedFeedUrlDropsOldHttpStateButKeepsUserSubscription() {
        val catalog = SourceCatalog.sources.single { it.id == "bbc-science" }
        val saved = catalog.copy(feedUrl = "https://example.org/retired.xml", enabled = true,
            etag = "old", lastModified = "old", lastFetchedAt = 123L, lastError = "Old endpoint failure")
        val refreshed = SourceCatalog.refresh(catalog, saved)
        assertEquals(catalog.feedUrl, refreshed.feedUrl)
        assertTrue(refreshed.enabled)
        assertNull(refreshed.etag)
        assertNull(refreshed.lastModified)
        assertNull(refreshed.lastFetchedAt)
        assertNull(refreshed.lastError)
    }

    @Test fun newRecordUsesDefaultsAndRefreshIsIdempotent() {
        SourceCatalog.sources.forEach { source ->
            assertEquals(source, SourceCatalog.refresh(source, null))
            assertEquals(source, SourceCatalog.refresh(source, source))
        }
        val conversation = SourceCatalog.sources.first()
        assertFalse(SourceCatalog.refresh(conversation, conversation.copy(enabled = false)).enabled)
    }
}
