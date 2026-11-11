package dev.lexicon.data

import dev.lexicon.domain.Topics
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class FeedEntry(
    val guid: String, val title: String, val url: String, val summary: String,
    val body: String?, val author: String, val publishedAt: Long?, val categories: List<String>,
    val rights: String,
)

/** Feed XML only. Jsoup does not fetch external DTDs, image resources or scripts. */
object FeedParser {
    fun parse(xml: String, baseUrl: String): List<FeedEntry> {
        require(xml.length <= 12_000_000) { "订阅源内容过大" }
        val doc = Jsoup.parse(xml, baseUrl, Parser.xmlParser())
        require(doc.children().firstOrNull()?.tagName()?.substringAfter(':')?.lowercase(Locale.ROOT) in setOf("rss", "feed", "rdf")) {
            "响应不是 RSS 或 Atom 订阅源"
        }
        val entries = doc.getElementsByTag("entry").ifEmpty { doc.getElementsByTag("item") }
        return entries.take(500).mapNotNull { e ->
            fun child(name: String) = e.children().firstOrNull { it.tagName().equals(name, true) }
            fun text(name: String) = child(name)?.text().orEmpty()
            fun html(name: String): String = child(name)?.let { node ->
                if (node.children().isEmpty()) node.text() else node.html()
            }.orEmpty()
            val link = e.children().firstOrNull { it.tagName() == "link" && it.attr("href").isNotBlank() && it.attr("rel") in listOf("", "alternate") }
                ?.attr("href") ?: text("link")
            val url = runCatching { URI(baseUrl).resolve(link).toString() }.getOrNull().orEmpty()
            if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
            val title = plain(text("title")).trim()
            if (title.isBlank()) return@mapNotNull null
            val bodyHtml = html("content:encoded").ifBlank { html("content") }
            val body = bodyHtml.takeIf { it.isNotBlank() }?.let(::bodyText)?.takeIf { it.isNotBlank() }
            val summaryHtml = html("summary").ifBlank { html("description") }
            // A body-only feed may supply no summary. Never persist its complete text as a summary.
            val summary = plain(summaryHtml).take(900)
            val author = child("author")?.let { a -> a.getElementsByTag("name").firstOrNull()?.text() ?: a.text() }
                ?: text("dc:creator")
            val categories = e.children().filter { it.tagName().lowercase(Locale.ROOT) in setOf("category", "dc:subject") }
                .map { it.attr("term").ifBlank { it.text() }.trim() }.filter(String::isNotBlank).distinct()
            FeedEntry(
                guid = text("id").ifBlank { text("guid") }.ifBlank { canonicalUrl(url) },
                title = title, url = canonicalUrl(url), summary = summary, body = body,
                author = author.take(500), publishedAt = listOf("published", "pubDate", "updated", "dc:date")
                    .firstNotNullOfOrNull { parseDate(text(it)) },
                categories = categories, rights = text("rights").take(1000),
            )
        }
    }

    fun plain(html: String): String = Jsoup.parseBodyFragment(html).apply { select("script,style,noscript,iframe").remove() }.text()

    fun bodyText(html: String): String {
        val body = Jsoup.parseBodyFragment(html).body()
        body.select("script,style,noscript,iframe,nav,form,button,svg,img,picture,video,audio,figure,footer").remove()
        body.select("br").forEach { it.after("\n") }
        body.select("p,h1,h2,h3,h4,h5,h6,li,blockquote,section,div").forEach { it.prependText("\n\n"); it.appendText("\n\n") }
        return body.wholeText().replace('\u00a0', ' ').replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
            .lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun canonicalUrl(raw: String): String = runCatching {
        val u = URI(raw.trim())
        val scheme = u.scheme?.lowercase(Locale.ROOT) ?: return@runCatching raw.trim()
        val host = u.host?.lowercase(Locale.ROOT) ?: return@runCatching raw.trim()
        val query = u.rawQuery?.split('&')?.filterNot {
            val key = it.substringBefore('=').lowercase(Locale.ROOT)
            key.startsWith("utm_") || key in setOf("fbclid", "gclid", "mc_cid", "mc_eid")
        }?.joinToString("&")?.takeIf(String::isNotEmpty)
        val port = if ((scheme == "https" && u.port == 443) || (scheme == "http" && u.port == 80)) -1 else u.port
        // Retain path case and non-tracking query parameters; do not merge different article pages.
        "$scheme://$host${if (port == -1) "" else ":$port"}${u.rawPath.ifBlank { "/" }}${query?.let { "?$it" }.orEmpty()}"
    }.getOrDefault(raw.trim())

    fun topic(categories: List<String>, default: String?): String? {
        for (c in categories) {
            val value = c.lowercase(Locale.ROOT)
            val result = when {
                value.contains("politic") || value == "world" -> "Politics"
                value.contains("science") || value.contains("tech") || value == "space" -> "Science & Technology"
                value.contains("econom") || value.contains("business") || value.contains("finance") -> "Business"
                value.contains("culture") || value.contains("arts") || value.contains("history") -> "Culture"
                value.contains("health") || value.contains("medic") -> "Health"
                value.contains("environment") || value.contains("energy") || value.contains("climate") -> "Environment"
                value.contains("education") || value.contains("school") -> "Education"
                value.contains("society") || value.contains("law") || value.contains("ethic") -> "Law & Society"
                else -> null
            }
            if (result != null) return result
        }
        return default?.takeIf { Topics.children.containsKey(it) }
    }

    private fun parseDate(raw: String): Long? = runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        // RSS1/Dublin Core may specify only a calendar date. A fixed UTC boundary avoids timezone-dependent ordering.
        ?: runCatching { LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
}

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
