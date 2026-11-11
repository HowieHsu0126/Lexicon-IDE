package dev.lexicon.data

import dev.lexicon.domain.ReadingMode
import dev.lexicon.domain.Source

/** Official feed endpoints checked on 2026-10-29; availability does not grant article or AI rights. */
object SourceCatalog {
    private const val SCIENCE = "Science & Technology"
    private const val EXTERNAL_NOTICE = "官方 feed；正文在 App 内阅读；未开启 AI 处理"

    val sources: List<Source> = listOf(
        Source("conversation-us", "The Conversation · US", "https://theconversation.com/us/articles.atom", ReadingMode.NATIVE,
            enabled = true, aiAllowed = true, license = "Creative Commons — Attribution / No Derivatives；个人 RSS 阅读，原文与 AI 注释分开",
            licenseUrl = "https://theconversation.com/us/republishing-guidelines"),
        external("economist-latest", "The Economist · Latest", "https://www.economist.com/latest/rss.xml"),
        external("economist-business", "The Economist · Business", "https://www.economist.com/business/rss.xml", "Business"),
        external("economist-finance", "The Economist · Finance & Economics", "https://www.economist.com/finance-and-economics/rss.xml", "Business"),
        external("economist-science", "The Economist · Science & Technology", "https://www.economist.com/science-and-technology/rss.xml", SCIENCE),
        Source("bbc-science", "BBC Science", "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml", ReadingMode.EXTERNAL,
            enabled = false, aiAllowed = false, license = "个人 RSS 阅读；页面在 App 内打开",
            licenseUrl = "https://www.bbc.co.uk/usingthebbc/terms-of-use/", defaultTopic = SCIENCE),
        Source("guardian-science", "The Guardian · Science", "https://www.theguardian.com/science/rss", ReadingMode.EXTERNAL,
            enabled = false, aiAllowed = false, license = "个人非商业 RSS 阅读；此来源不提供 AI 处理",
            licenseUrl = "https://www.theguardian.com/help/terms-of-service", defaultTopic = SCIENCE),
        external("ft-home", "Financial Times · Headlines", "https://www.ft.com/rss/home"),
        external("nyt-science", "The New York Times · Science", "https://rss.nytimes.com/services/xml/rss/nyt/Science.xml", SCIENCE),
        external("atlantic", "The Atlantic", "https://www.theatlantic.com/feed/all/"),
        external("wired-science", "WIRED · Science", "https://www.wired.com/feed/category/science/latest/rss", SCIENCE),
        external("mit-tech-review", "MIT Technology Review", "https://www.technologyreview.com/feed/", SCIENCE),
        external("scientific-american-current", "Scientific American", "https://www.scientificamerican.com/platform/syndication/rss/", SCIENCE),
        external("nature", "Nature · Journal Feed", "https://www.nature.com/nature.rss", SCIENCE),
        external("npr-science", "NPR · Science", "https://feeds.npr.org/1007/rss.xml", SCIENCE),
        external("guardian-world", "The Guardian · World", "https://www.theguardian.com/world/rss", "Politics"),
        external("guardian-environment", "The Guardian · Environment", "https://www.theguardian.com/environment/rss", "Environment"),
        external("ars-technica", "Ars Technica", "https://feeds.arstechnica.com/arstechnica/index", SCIENCE),
    )

    /** Catalog updates own metadata; the saved record owns subscription and successful HTTP state. */
    fun refresh(catalog: Source, saved: Source?): Source {
        if (saved == null) return catalog
        require(saved.id == catalog.id) { "Cannot refresh a different source" }
        val sameUrl = saved.feedUrl == catalog.feedUrl
        return catalog.copy(
            enabled = saved.enabled,
            etag = saved.etag.takeIf { sameUrl },
            lastModified = saved.lastModified.takeIf { sameUrl },
            lastFetchedAt = saved.lastFetchedAt.takeIf { sameUrl },
            lastError = saved.lastError.takeIf { sameUrl },
        )
    }

    private fun external(id: String, name: String, url: String, topic: String? = null) = Source(
        id = id, name = name, feedUrl = url, readingMode = ReadingMode.EXTERNAL,
        enabled = false, aiAllowed = false, license = EXTERNAL_NOTICE, defaultTopic = topic,
    )
}
