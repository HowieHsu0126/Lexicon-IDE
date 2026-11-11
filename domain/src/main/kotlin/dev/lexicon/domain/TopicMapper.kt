package dev.lexicon.domain

import java.util.Locale

object TopicMapper {
    /** This maps publisher-provided categories only. Missing article labels go to title-only AI. */
    fun fromCategories(categories: List<String>, defaultTopic: String? = null): TopicResult? {
        for (category in categories) {
            val key = category.trim().lowercase(Locale.ROOT)
            for ((topic, children) in Topics.children) {
                if (key == topic.lowercase(Locale.ROOT)) return TopicResult("", topic, null)
                children.firstOrNull { it.lowercase(Locale.ROOT) == key }?.let { return TopicResult("", topic, it) }
            }
            aliases[key]?.let { return TopicResult("", it.first, it.second) }
        }
        return defaultTopic?.takeIf { it in Topics.children }?.let { TopicResult("", it, null) }
    }

    fun isValid(result: TopicResult): Boolean = result.topic?.let { topic ->
        Topics.children[topic]?.let { result.subtopic == null || result.subtopic in it } ?: false
    } ?: (result.subtopic == null)

    private val aliases = mapOf(
        "science" to ("Science & Technology" to null),
        "technology" to ("Science & Technology" to null),
        "science + technology" to ("Science & Technology" to null),
        "science + tech" to ("Science & Technology" to null),
        "artificial intelligence" to ("Science & Technology" to "AI"),
        "arts + culture" to ("Culture" to "Arts"),
        "arts" to ("Culture" to "Arts"),
        "economy" to ("Business" to "Economics"),
        "environment + energy" to ("Environment" to "Energy"),
        "politics + society" to ("Politics" to null),
        "ethics + religion" to ("Law & Society" to "Ethics"),
        "climate change" to ("Environment" to "Climate"),
        "higher education" to ("Education" to "Higher Education"),
    )
}
