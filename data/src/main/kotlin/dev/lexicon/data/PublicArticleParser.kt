package dev.lexicon.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Extracts only ordinary article HTML. Subscription/login documents remain in the in-app page. */
object PublicArticleParser {
    private val selectors = listOf("[itemprop=articleBody]", "article", ".article-body", ".article__body", ".entry-content", ".post-content", "main")
    private val excluded = "script,style,noscript,nav,header,footer,aside,form,button,figure,img,picture,video,audio,iframe,svg," +
        "[hidden],[aria-hidden=true],.advertisement,.ad-container,.related-content,.related-articles,.newsletter,.share-tools,.social-share,.comments"

    fun extract(html: String, url: String): String? {
        if (html.length > 6_000_000) return null
        val doc = Jsoup.parse(html, url)
        // A paid schema is not an invitation to recover text from script payloads or concealed HTML.
        if (doc.select("script[type=application/ld+json]").any {
                Regex("\"isAccessibleForFree\"\\s*:\\s*(?:false|\"false\")", RegexOption.IGNORE_CASE).containsMatchIn(it.data())
            }) return null
        if (doc.select("[class*=paywall],[id*=paywall],[data-paywall],[class*=subscription-gate]").isNotEmpty()) return null
        // Respect simple stylesheet visibility rules before removing style nodes. Complex pages
        // remain available in WebView, where the publisher's full CSS/JS access behavior applies.
        doc.select("style").forEach { style ->
            Regex("([^{}]+)\\{([^{}]*)}").findAll(style.data()).forEach { rule ->
                if (Regex("display\\s*:\\s*none|visibility\\s*:\\s*hidden", RegexOption.IGNORE_CASE).containsMatchIn(rule.groupValues[2])) {
                    runCatching { doc.select(rule.groupValues[1].trim()).remove() }
                }
            }
        }
        doc.select(excluded).remove()
        doc.select("[style]").filter {
            Regex("(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)", RegexOption.IGNORE_CASE).containsMatchIn(it.attr("style"))
        }.forEach(Element::remove)
        // Prefer the actual article body over a longer page-level main containing other stories.
        val candidates = selectors.firstNotNullOfOrNull { selector -> doc.select(selector).takeIf { it.isNotEmpty() } } ?: return null
        return candidates.mapNotNull { container ->
            val blocks = container.select("p,h2,h3,li,blockquote").filter { el ->
                el.parents().takeWhile { it != container }.none { it.tagName() in setOf("p", "li", "blockquote") }
            }.map { it.text().trim() }.filter { it.length > 1 }
            if (blocks.size < 3 || blocks.count { it.length >= 70 } < 3) return@mapNotNull null
            val text = blocks.joinToString("\n\n")
            if (text.length !in 400..160_000) return@mapNotNull null
            if (Regex("(?:subscribe|sign in|log in|register) (?:now |today )?to (?:continue|read|unlock)|already a subscriber", RegexOption.IGNORE_CASE)
                    .containsMatchIn(text)) return@mapNotNull null
            text
        }.maxByOrNull { it.length }
    }

    fun isSubstantial(text: String?): Boolean = text != null && text.length in 400..160_000 && text.split("\n\n").count { it.length >= 70 } >= 3
}
