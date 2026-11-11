package dev.lexicon.ide.ui

import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.lexicon.domain.*
import dev.lexicon.ide.LexiconApplication
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/** Test-only ownership ledger. Never restores a database or changes an original row. */
internal class SpeedSetupTrialStore(
    val app: LexiconApplication, run: String, val started: Long, val report: JSONObject,
) {
    val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val database = app.getDatabasePath("lexicon.sqlite")
    val original = quietRows()
    val fixture = Fixture(run, started)
    val dictionaryFiles = fileSignatures(File(app.noBackupFilesDir, "dictionary"))
    val dictionaryId = runBlocking { app.repository.dictionaryStatus() }.id
    var inserted = false
    var readerOpened = false
    var setupActions = 0
    val allowedRates = SpeedGranularity.entries.associateWith { linkedSetOf(60) }
    val confirmedProfiles = linkedSetOf<String>()
    private val day = Instant.ofEpochMilli(started).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    private var expectedEdition: DailyEdition? = null

    fun preflight() {
        offlineNoKey(app)
        check(original.keys == TABLES) { "Unexpected schema: require the complete thirteen-table snapshot" }
        listOf("articles", "article_sources", "article_bodies", "captures", "cards", "review_logs", "ai_jobs").forEach {
            check(original.getValue(it).isEmpty()) { "Dedicated trial user has existing learning material" }
        }
        check(original.getValue("preferences").none { row ->
            row[0] in listOf("settings", PROFILE) || row[0].orEmpty().startsWith("personalization:") ||
                listOf("speed-", "baseline-", "reading-library:session:", "reading-library:note:").any { row[0].orEmpty().startsWith(it) }
        }) { "Trial requires absent settings, personalization and learning records" }
        check(runBlocking { app.repository.settings.first() } == AppSettings())
        check(runBlocking { requireNotNull(app.repository.personalization).state.first() } == PersonalizationProfile())
        check(dictionaryFiles.isNotEmpty() && dictionaryId.isNotBlank())
        val editions = original.getValue("preferences").filter { it[0].orEmpty().startsWith("reading-library:edition:") }
        val existing = editions.singleOrNull { it[0] == "reading-library:edition:$day" }?.let { codec.decodeFromString<DailyEdition>(requireNotNull(it[1])) }
        val history = editions.sortedByDescending { it[0] }.take(8).map { codec.decodeFromString<DailyEdition>(requireNotNull(it[1])) }
        expectedEdition = ReadingDailyPlanner.extend(existing, listOf(fixture.article),
            runBlocking { app.repository.sources.first() } + fixture.source, started, ZoneId.systemDefault().id, history)
        check(existing == null || existing == expectedEdition) { "Opening the app would rewrite an original daily edition" }
        report.put("originalTableHashes", tableHashes(original)).put("originalSnapshotKeptOnlyInMemory", true)
            .put("originalSettingsRowAbsent", true).put("originalProfileRowAbsent", true)
            .put("initialProfile", JSONObject(codec.encodeToString(PersonalizationProfile.serializer(), PersonalizationProfile())))
            .put("dictionaryFilesBefore", JSONObject(dictionaryFiles)).put("selectedDictionarySha256", dictionaryId)
            .put("bodySha256", fixture.body.contentHash).put("sourceParagraphs", 4).put("sourceWords", fixture.article.wordCount)
    }

    fun insertFixture() {
        open().use { db -> transaction(db) {
            check(snapshot(db) == original)
            insert(db, "sources", "id" to fixture.source.id, "data" to codec.encodeToString(Source.serializer(), fixture.source))
            insert(db, "articles", "id" to fixture.article.id, "url" to fixture.article.url,
                "publishedAt" to requireNotNull(fixture.article.publishedAt), "data" to codec.encodeToString(Article.serializer(), fixture.article))
            insert(db, "article_sources", "sourceId" to fixture.source.id, "guid" to fixture.article.guid, "articleId" to fixture.article.id)
            insert(db, "article_bodies", "articleId" to fixture.body.articleId, "expiresAt" to fixture.body.expiresAt,
                "data" to codec.encodeToString(ArticleBody.serializer(), fixture.body))
        } }
        inserted = true
        report.put("fixtureRowsInserted", 4)
    }

    /** Called only after a real action and a settled visible value; no profile mutation here. */
    fun observeProfile(level: SpeedGranularity, visibleRate: Int, action: String, phase: String, afterExit: Boolean = false) {
        val row = rows().getValue("preferences").singleOrNull { it[0] == PROFILE }
        val receipt = JSONObject().put("afterAction", action).put("present", row != null)
        if (row != null) {
            val raw = requireNotNull(row[1]); val profile = codec.decodeFromString<PersonalizationProfile>(raw)
            check(raw == codec.encodeToString(PersonalizationProfile.serializer(), profile))
            check(profile.enabled && !profile.sentenceOnly && profile.decisions.isEmpty() && profile.holds.isEmpty() &&
                profile.activePaceBinding == null && profile.undoPaceBindings.isEmpty())
            check(profile.revision in 1L..(setupActions + 2).toLong())
            check(profile.rates.keys == SpeedGranularity.entries.toSet())
            check(profile.rates.all { (granularity, rate) -> rate in allowedRates.getValue(granularity) })
            check(profile.granularity == level && profile.rateFor(level) == visibleRate) { "Profile does not match the observed UI choice" }
            if (phase == "before") {
                check(afterExit && profile == PersonalizationProfile(revision = 1, granularity = level,
                    rates = PersonalizationProfile().rates + (level to visibleRate)))
            }
            confirmedProfiles += raw
            receipt.put("sha256", sha(raw)).put("revision", profile.revision).put("granularity", profile.granularity.name)
                .put("rates", JSONObject(profile.rates.mapKeys { it.key.name }))
        } else check(phase == "before" || setupActions == 0) { "Committed setup settings were not saved" }
        report.getJSONArray("profileStates").put(receipt)
    }

    fun rows() = open(true).use { db ->
        db.exec("BEGIN DEFERRED TRANSACTION")
        try { snapshot(db).also { db.exec("COMMIT") } } catch (e: Throwable) { runCatching { db.exec("ROLLBACK") }; throw e }
    }

    fun quietRows(): Map<String, List<List<String?>>> {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var before = rows(); var since = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(200)
            val next = rows()
            if (next != before) { before = next; since = SystemClock.elapsedRealtime() }
            else if (SystemClock.elapsedRealtime() - since >= 2_000) return next
        }
        error("Database writers did not settle")
    }

    fun preserve() {
        offlineNoKey(app)
        val settled = quietRows()
        if (inserted) cleanup(settled)
        val final = quietRows()
        check(final == original) { "Original table rows differ; do not restore them" }
        val afterDictionary = fileSignatures(File(app.noBackupFilesDir, "dictionary"))
        check(afterDictionary == dictionaryFiles)
        check(runBlocking { app.repository.dictionaryStatus() }.id == dictionaryId)
        report.put("finalTableHashes", tableHashes(final)).put("allOriginalRowsCompared", true)
            .put("dictionaryFilesAfter", JSONObject(afterDictionary)).put("dictionaryFilesPreserved", true)
            .put("dictionarySelectionUnchanged", true)
            .put("settingsRowAbsentAfterCleanup", final.getValue("preferences").none { it[0] == "settings" })
            .put("profileRowAbsentAfterCleanup", final.getValue("preferences").none { it[0] == PROFILE }).put("originalDataPreserved", true)
    }

    private fun cleanup(settled: Map<String, List<List<String?>>>) {
        check(settled.keys == original.keys)
        val added = settled.mapValues { (table, rows) ->
            val before = original.getValue(table); val keys = before.map { key(table, it) }.toSet()
            check(rows.filter { key(table, it) in keys } == before) { "An original row changed" }
            rows.filter { key(table, it) !in keys }
        }
        val end = System.currentTimeMillis(); val f = fixture
        val sessions = added.getValue("preferences").filter { it[0].orEmpty().startsWith("reading-library:session:") }
            .associate { requireNotNull(it[0]).removePrefix("reading-library:session:") to codec.decodeFromString<ReadingSession>(requireNotNull(it[1])) }
        added.forEach { (table, values) -> values.forEach { row ->
            val id = requireNotNull(row[0])
            val owned = when (table) {
                "sources" -> id == f.source.id && codec.decodeFromString<Source>(requireNotNull(row[1])) == f.source
                "articles" -> id == f.article.id && row[1] == f.article.url && row[2]?.toLongOrNull() == f.article.publishedAt &&
                    codec.decodeFromString<Article>(requireNotNull(row[3])) == f.article
                "article_sources" -> row == listOf(f.source.id, f.article.guid, f.article.id)
                "article_bodies" -> id == f.article.id && row[1]?.toLongOrNull() == f.body.expiresAt &&
                    codec.decodeFromString<ArticleBody>(requireNotNull(row[2])) == f.body
                "local_events" -> codec.decodeFromString<LocalEvent>(requireNotNull(row[2])).let { event ->
                    event.id == id && event.createdAt == row[1]?.toLongOrNull() && event.createdAt in started..end && when (event.type) {
                        "zen_opened" -> readerOpened && event.value == 1
                        "zen_duration_seconds" -> readerOpened && event.value >= 0
                        "reading_session" -> id.startsWith("reading-session:") && sessions[id.removePrefix("reading-session:")]?.let {
                            it.articleId == f.article.id && it.startedAt in started..end && event.createdAt == it.finishedAt && event.value == 1
                        } == true
                        else -> false
                    }
                }
                "preferences" -> requireNotNull(row[1]).let { data -> when {
                    id == "reading-library:edition:$day" -> codec.decodeFromString<DailyEdition>(data) == expectedEdition
                    id == PROFILE -> original.getValue("preferences").none { it[0] == PROFILE } && data == confirmedProfiles.lastOrNull()
                    !readerOpened -> false
                    id.startsWith("reading-library:session:") -> sessions[id.removePrefix("reading-library:session:")]?.let {
                        it.id == id.removePrefix("reading-library:session:") && it.articleId == f.article.id &&
                            it.startedAt in started..end && it.finishedAt in it.startedAt..end && it.totalParagraphs == 4 &&
                            it.level == "原文" && !it.completed && it.assistanceCount == 0
                    } == true
                    id == "reading-exposure:article:${f.article.id}" || id == "reading-exposure:body:${f.body.contentHash}" -> data.toLongOrNull()?.let { it in started..end } == true
                    id == "reading-resume:record:${sha(f.article.id)}" -> codec.decodeFromString<ReadingResumeRecord>(data).let {
                        ReadingResumes.validate(it, f.body); it.updatedAt in started..end && !it.completed
                    }
                    id == "reading-resume:generation:${sha(f.article.id)}" -> JSONObject(data).let {
                        it.getInt("version") == 1 && it.getLong("generation") > 0 && it.getString("bodyHash") == f.body.contentHash
                    }
                    else -> false
                } }
                else -> false
            }
            check(owned) { "Unexpected added row; retain all rows for inspection" }
        } }
        report.put("noTrainingRecordsGenerated", added.getValue("preferences").none { it[0].orEmpty().startsWith("speed-") })
        open().use { db -> transaction(db) {
            check(snapshot(db) == settled)
            listOf("local_events", "preferences", "article_bodies", "article_sources", "articles", "sources").forEach { table ->
                added.getValue(table).forEach { row ->
                    val where = when (table) {
                        "preferences" -> "`key`=? AND data=?"
                        "article_sources" -> "sourceId=? AND guid=?"
                        "article_bodies" -> "articleId=?"
                        else -> "id=?"
                    }
                    db.prepare("DELETE FROM $table WHERE $where").use { statement ->
                        statement.bindText(1, requireNotNull(row[0]))
                        if (table == "preferences" || table == "article_sources") statement.bindText(2, requireNotNull(row[1]))
                        statement.step()
                    }
                    check(changes(db) == 1L)
                }
            }
        } }
        report.put("ownedReadingSessions", sessions.size).put("removedOwnedProfileRows", added.getValue("preferences").count { it[0] == PROFILE })
            .put("removedOwnedRows", JSONObject().apply { added.forEach { (table, values) -> if (values.isNotEmpty()) put(table, values.size) } })
    }

    private fun open(queryOnly: Boolean = false): SQLiteConnection {
        check(database.isFile)
        val db = BundledSQLiteDriver().open(database.absolutePath)
        try { db.exec("PRAGMA busy_timeout=5000"); if (queryOnly) db.exec("PRAGMA query_only=ON"); return db }
        catch (e: Throwable) { db.close(); throw e }
    }
    private fun insert(db: SQLiteConnection, table: String, vararg values: Pair<String, Any>) {
        val columns = values.joinToString(",") { "\"${it.first}\"" }; val marks = values.joinToString(",") { "?" }
        db.prepare("INSERT INTO $table ($columns) VALUES ($marks)").use { statement ->
            values.forEachIndexed { i, (_, value) -> when (value) {
                is String -> statement.bindText(i + 1, value)
                is Long -> statement.bindLong(i + 1, value)
                else -> error("Unsupported fixture type")
            } }; statement.step()
        }; check(changes(db) == 1L)
    }
    private fun transaction(db: SQLiteConnection, block: () -> Unit) {
        db.exec("BEGIN IMMEDIATE TRANSACTION")
        try { block(); db.exec("COMMIT") } catch (e: Throwable) { runCatching { db.exec("ROLLBACK") }; throw e }
    }
    private fun SQLiteConnection.exec(sql: String) { prepare(sql).use { it.step() } }
    private fun changes(db: SQLiteConnection) = db.prepare("SELECT changes()").use { check(it.step()); it.getLong(0) }
    private fun key(table: String, row: List<String?>) = if (table == "article_sources") JSONArray(row.take(2)).toString() else requireNotNull(row[0])
    private fun snapshot(db: SQLiteConnection): Map<String, List<List<String?>>> {
        val tables = db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use {
            buildList { while (it.step()) add(it.getText(0)) }
        }
        return tables.associateWith { table ->
            require(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            db.prepare("SELECT * FROM \"$table\"").use { statement ->
                buildList { while (statement.step()) add((0 until statement.getColumnCount()).map { if (statement.isNull(it)) null else statement.getText(it) }) }
                    .sortedBy { JSONArray(it).toString() }
            }
        }
    }
    internal class Fixture(run: String, now: Long) {
        val source = Source("speed-setup:$run:source", "自写速读设置试用", "https://example.invalid/speed-setup/$run/feed",
            readingMode = ReadingMode.NATIVE, enabled = false, aiAllowed = false,
            license = "Self-authored fictional test prose, dedicated to the public domain under CC0.")
        private val paragraphs = listOf(
            "A fictional reading group meets in a quiet room above a small library. Each reader chooses a short passage and decides how much time to spend with it. The group is interested in a comfortable rhythm rather than a competition. These sentences are self-authored test material and do not report events in a real community.",
            "Before starting, one reader changes the size of each sense group. Longer groups can keep a phrase together, while shorter groups offer more visible pauses. The choice should remain separate from the pace that the reader has just selected. A useful control lets the person explore both settings without losing a deliberate choice.",
            "Another reader tries two different settings and then returns to the first. The page should show the choice made earlier in the same preparation session. Repeating a tap on the already selected option should not quietly replace that choice. The example concerns an interface, not a measurement of comprehension or a claim about learning.",
            "The group closes the preparation page without beginning any exercise. No answer is submitted and no training result is created. The text remains available for ordinary reading. A test of the controls can therefore check visible values and local records without pretending that anybody completed a reading lesson or improved their ability."
        )
        val text = paragraphs.joinToString("\n\n")
        val article = Article("speed-setup:$run:article", "speed-setup:$run:guid", listOf(source.id),
            "速读设置试用 · ${run.takeLast(8)}", "https://example.invalid/speed-setup/$run/article", "自写四段交互试用材料。",
            author = "Lexicon instrumentation fixture", publishedAt = now, topic = "Culture", topicOrigin = "synthetic_fixture",
            read = true, cachedAt = now, readingMode = ReadingMode.NATIVE, sourceName = source.name, license = source.license,
            wordCount = Regex("[A-Za-z]+(?:['-][A-Za-z]+)*").findAll(text).count())
        val body = ArticleBody(article.id, text, sha(text), now, now + 3_600_000, source.license, article.author)
    }
    companion object {
        const val PROFILE = "personalization:profile"
        private val TABLES = setOf("ai_jobs", "android_metadata", "article_bodies", "article_sources", "articles", "captures", "cards", "local_events", "preferences", "review_logs", "room_master_table", "sources", "topic_cache")
        fun offlineNoKey(app: LexiconApplication) {
            check(app.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
            check(!runBlocking { app.repository.hasApiKey() })
        }
        fun sha(text: String) = digest(text.toByteArray(Charsets.UTF_8))
        fun sha(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun tableHashes(rows: Map<String, List<List<String?>>>) = JSONObject().apply {
            rows.forEach { (table, values) -> put(table, JSONObject().put("rows", values.size).put("sha256", sha(JSONArray(values.map { JSONArray(it) }).toString()))) }
        }
        fun fileSignatures(folder: File): Map<String, String> = if (!folder.isDirectory) emptyMap() else
            folder.walkTopDown().filter { it.isFile }.associate { it.relativeTo(folder).invariantSeparatorsPath to "${it.length()}:${sha(it)}" }.toSortedMap()
    }
}
