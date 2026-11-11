package dev.lexicon.domain

import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** A fixed daily choice. New synchronization may fill empty slots, never replace existing ones. */
object ReadingDailyPlanner {
    fun extend(existing: DailyEdition?, articles: List<Article>, sources: List<Source>, now: Long,
        zoneId: String = "Asia/Shanghai", history: List<DailyEdition> = emptyList()): DailyEdition {
        val day = Instant.ofEpochMilli(now).atZone(ZoneId.of(zoneId)).toLocalDate().toString()
        val frozen = existing?.takeIf { it.day == day } ?: DailyEdition(day, emptyList(), emptyList())
        require(frozen.articleIds.size <= 3 && frozen.articleIds.distinct().size == frozen.articleIds.size &&
            frozen.publisherKeys.distinct().size == frozen.publisherKeys.size && frozen.publisherKeys.none { it.isBlank() })
        val sourceMap = sources.associateBy { it.id }
        val ids = frozen.articleIds.toMutableList()
        // This is an occupied-publisher set, not a list zipped one-to-one with articleIds.
        val publishers = frozen.publisherKeys.toCollection(linkedSetOf())
        articles.filter { it.id in ids }.sortedBy { ids.indexOf(it.id) }.forEach { publishers.addAll(publisherKeys(it, sourceMap)) }
        if (ids.size == 3) return DailyEdition(day, ids, publishers.toList())
        val recentDays = history.filter { it.day < day }.sortedByDescending { it.day }.distinctBy { it.day }.take(7)
        val counts = recentDays.flatMap { it.publisherKeys.distinct() }.groupingBy { it }.eachCount()
        data class Candidate(val article: Article, val publishers: Set<String>)
        val candidates = articles.distinctBy { it.id }.filter { article ->
            article.id !in ids && (article.publishedAt ?: now) <= now && article.sourceIds.isNotEmpty() &&
                article.sourceIds.all { sourceMap.containsKey(it) } && article.sourceIds.any { sourceMap[it]?.enabled == true }
        }.map { Candidate(it, publisherKeys(it, sourceMap)) }.sortedWith(
            compareBy<Candidate> { it.article.read }
                .thenBy { if (now - (it.article.publishedAt ?: it.article.cachedAt) <= 7L * 86_400_000) 0 else 1 }
                .thenBy { it.publishers.maxOfOrNull { publisher -> counts[publisher] ?: 0 } ?: 0 }
                .thenByDescending { it.article.publishedAt ?: it.article.cachedAt }
                .thenBy { it.publishers.sorted().joinToString("|") }.thenBy { it.article.id })
        for (candidate in candidates) {
            if (ids.size == 3) break
            if (candidate.publishers.any { it in publishers }) continue
            ids += candidate.article.id
            publishers.addAll(candidate.publishers)
        }
        return DailyEdition(day, ids, publishers.toList())
    }

    fun publisherKey(article: Article, sources: List<Source>): String = primaryPublisher(article, sources.associateBy { it.id })
    fun publisherKeys(article: Article, sources: List<Source>): Set<String> = publisherKeys(article, sources.associateBy { it.id })

    private fun publisherKeys(article: Article, sources: Map<String, Source>): Set<String> = linkedSetOf<String>().apply {
        add(primaryPublisher(article, sources))
        // A canonical URL may have been syndicated by several sources. All of them occupy slots.
        article.sourceIds.distinct().sorted().mapNotNull { sources[it] }.forEach { source ->
            add(publicationHost(source.feedUrl) ?: publicationName(source.name))
        }
    }
    private fun primaryPublisher(article: Article, sources: Map<String, Source>): String =
        publicationHost(article.url) ?: article.sourceIds.sorted().firstNotNullOfOrNull { sources[it]?.feedUrl?.let(::publicationHost) }
        ?: publicationName(article.sourceIds.sorted().firstNotNullOfOrNull { sources[it]?.name } ?: article.sourceName)

    private fun publicationHost(url: String): String? {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT)?.removeSuffix(".")?.removePrefix("www.") }.getOrNull() ?: return null
        val parts = host.split('.')
        val root = if (parts.takeLast(2).joinToString(".") in setOf("co.uk", "com.au", "co.nz")) parts.takeLast(3).joinToString(".") else parts.takeLast(2).joinToString(".")
        return when (root) { "bbc.com", "bbc.co.uk", "bbci.co.uk" -> "bbc"; "guardian.co.uk", "theguardian.com" -> "guardian"; else -> root }
    }
    private fun publicationName(name: String): String = name.substringBefore('·').trim().lowercase(Locale.ROOT).ifBlank { "unknown-publisher" }
}

data class ReadingBadge(val id: String, val title: String, val detail: String, val achieved: Boolean)
data class TopicReadingProgress(val topic: String, val completed: Int, val activeMinutes: Double, val comprehensionPercent: Double?)
data class ReadingProgress(val completed: Int, val readingDays: Int, val activeMinutes: Double, val wordsRead: Int,
    val wordsPerMinute: Double?, val comprehensionPercent: Double?, val growthPoints: Int,
    val badges: List<ReadingBadge>, val topics: List<TopicReadingProgress>,
    val note: String = "速度按有效记录的字数和活跃时长估算；理解反馈取每题最近一次评分，不代表考试成绩。成长点只累计完成、笔记和练习记录。",
    val speedSampleCount: Int = 0, val evaluatedQuestions: Int = 0, val practiceAttempts: Int = 0)

object ReadingProgressCalculator {
    fun summarize(library: ReadingLibrary, zoneId: String = "Asia/Shanghai"): ReadingProgress {
        val zone = ZoneId.of(zoneId)
        val sessions = library.sessions.filter { session ->
            session.id.isNotBlank() && session.articleId.isNotBlank() && session.startedAt >= 0 && session.finishedAt >= session.startedAt &&
                session.activeMillis in 0..(session.finishedAt - session.startedAt) && session.activeMillis <= 86_400_000 &&
                session.wordsRead in 0..1_000_000 && session.totalParagraphs in 0..10000 && session.paragraphsRead in 0..session.totalParagraphs && session.assistanceCount >= 0
        }.groupBy { it.id }.values.mapNotNull { snapshots ->
            if (snapshots.map { it.articleId to it.startedAt }.distinct().size != 1) return@mapNotNull null
            val latest = snapshots.maxWithOrNull(compareBy<ReadingSession> { it.finishedAt }.thenBy { it.activeMillis }) ?: return@mapNotNull null
            latest.copy(activeMillis = snapshots.maxOf { it.activeMillis }, wordsRead = snapshots.maxOf { it.wordsRead },
                paragraphsRead = snapshots.maxOf { it.paragraphsRead }, totalParagraphs = snapshots.maxOf { it.totalParagraphs },
                assistanceCount = snapshots.maxOf { it.assistanceCount }, completed = snapshots.any { it.completed })
        }
        val completed = sessions.count { it.completed && it.activeMillis > 0 && it.wordsRead > 0 }
        val activeMillis = sessions.sumOf { it.activeMillis.toDouble() }
        val words = sessions.filter { it.activeMillis > 0 }.sumOf { it.wordsRead.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        // Zero-time and short page visits must not inflate the numerator of an estimated speed.
        val speedSamples = sessions.filter { it.activeMillis >= 30_000 && it.wordsRead > 0 }
        val speedMillis = speedSamples.sumOf { it.activeMillis.toDouble() }
        val attempts = library.checks.filter { it.id.isNotBlank() && it.articleId.isNotBlank() && it.questionId.isNotBlank() &&
            it.createdAt >= 0 && it.score.isFinite() && it.score in 0.0..1.0 && it.answer.isNotBlank() && it.evaluatedBy.isNotBlank() }
            .groupBy { it.id }.values.map { group -> group.maxWith(compareBy<ComprehensionCheck> { it.createdAt }.thenBy { it.articleId }.thenBy { it.questionId }) }
        val checks = attempts.groupBy { it.articleId to it.questionId }.values
            .map { group -> group.maxWith(compareBy<ComprehensionCheck> { it.createdAt }.thenBy { it.id }) }
        val days = sessions.filter { it.activeMillis > 0 }.map { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }.distinct().size
        fun average(values: List<ComprehensionCheck>) = values.takeIf { it.isNotEmpty() }?.map { it.score }?.average()?.times(100)
        fun topic(value: String?) = value?.trim()?.takeIf { it.isNotBlank() } ?: "未分类"
        val topics = (sessions.map { topic(it.topic) } + checks.map { topic(it.topic) }).distinct().sorted().map { name ->
            val rows = sessions.filter { topic(it.topic) == name }
            TopicReadingProgress(name, rows.count { it.completed && it.activeMillis > 0 && it.wordsRead > 0 }, rows.sumOf { it.activeMillis.toDouble() } / 60000.0,
                average(checks.filter { topic(it.topic) == name }))
        }
        val notes = library.notes.filter { it.id.isNotBlank() && (it.quote.isNotBlank() || it.comment.isNotBlank()) }.distinctBy { it.id }.size
        val badges = listOf(
            ReadingBadge("first-reading", "完成首篇", "完成 1 次阅读", completed >= 1),
            ReadingBadge("three-readings", "三次阅读", "完成 3 次阅读", completed >= 3),
            ReadingBadge("seven-days", "七日阅读", "在 7 个不同日期留下阅读记录", days >= 7),
            ReadingBadge("five-notes", "五条笔记", "保存 5 条阅读笔记", notes >= 5),
        )
        val points = (completed.toLong() * 10 + notes.toLong() * 2 + checks.size.toLong() * 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return ReadingProgress(completed, days, activeMillis / 60000.0, words,
            if (speedMillis > 0) speedSamples.sumOf { it.wordsRead.toDouble() } * 60000.0 / speedMillis else null,
            average(checks), points, badges, topics, speedSampleCount = speedSamples.size,
            evaluatedQuestions = checks.size, practiceAttempts = attempts.size)
    }
}
