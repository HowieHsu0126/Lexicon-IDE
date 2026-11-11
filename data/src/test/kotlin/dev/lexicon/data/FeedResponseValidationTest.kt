package dev.lexicon.data

import org.junit.Test

class FeedResponseValidationTest {
    @Test(expected = IllegalArgumentException::class)
    fun htmlAccessPageCannotMasqueradeAsSuccessfulEmptyFeed() {
        FeedParser.parse("<html><body>Please sign in</body></html>", "https://example.com/feed")
    }
}
