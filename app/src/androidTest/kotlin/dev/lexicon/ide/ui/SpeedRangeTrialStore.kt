package dev.lexicon.ide.ui

import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.AtomicFile
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
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.json.JSONArray
import org.json.JSONObject

/** Test-only ownership ledger. Never restores a database or changes an original row. */
internal class SpeedRangeTrialStore(
    val app: LexiconApplication, private val run: String, val started: Long, val report: JSONObject,
    private val output: File,
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
    val confirmedSettings = linkedSetOf<String>()
    val confirmedPlanRows = linkedMapOf<String, String>()
    val confirmedRequestRows = linkedMapOf<String, String>()
    val plans = linkedMapOf<String, SpeedPlan>()
    private val day = Instant.ofEpochMilli(started).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    private var expectedEdition: DailyEdition? = null
    private var ledgerReady = false
    private var ledgerSequence = 0L
    private var cleanupCompleted = false

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
        check(output.isDirectory && output.canonicalPath.startsWith(app.filesDir.canonicalPath + File.separator))
        check(!File(output, "ownership-ledger.json").exists())
        ledgerReady = true
        report.put("originalTableHashes", tableHashes(original)).put("originalSnapshotKeptOnlyInMemory", false)
            .put("originalSettingsRowAbsent", true).put("originalProfileRowAbsent", true)
            .put("initialProfile", JSONObject(codec.encodeToString(PersonalizationProfile.serializer(), PersonalizationProfile())))
            .put("dictionaryFilesBefore", JSONObject(dictionaryFiles)).put("selectedDictionarySha256", dictionaryId)
            .put("bodySha256", fixture.body.contentHash).put("sourceParagraphs", 1).put("sourceWords", fixture.article.wordCount)
        persistOwnership("preflight_confirmed")
    }

    /** Private recovery evidence only. This method never reads or rewrites live database rows. */
    fun persistOwnership(stage: String) {
        if (!ledgerReady) return
        val ledger = JSONObject().put("schema", "lexicon.speed.range.ownership.v1")
            .put("privateEvidence", true).put("publishWithPublicReport", false)
            .put("containsExactDatabaseRowsAndSelfAuthoredText", true)
            .put("uniqueRun", run).put("startedAt", started).put("day", day)
            .put("sequence", ++ledgerSequence).put("recordedAt", System.currentTimeMillis()).put("stage", stage)
            .put("androidUserId", 10).put("apkSha256", report.getString("apkSha256"))
            .put("originalTables", JSONObject().apply {
                original.forEach { (table, rows) -> put(table, JSONArray(rows.map { JSONArray(it) })) }
            }).put("originalTableHashes", tableHashes(original))
            .put("fixture", JSONObject()
                .put("source", codec.encodeToString(Source.serializer(), fixture.source))
                .put("article", codec.encodeToString(Article.serializer(), fixture.article))
                .put("articleSource", JSONArray(listOf(fixture.source.id, fixture.article.guid, fixture.article.id)))
                .put("body", codec.encodeToString(ArticleBody.serializer(), fixture.body)))
            .put("fixtureInserted", inserted).put("readerOpened", readerOpened).put("setupActions", setupActions)
            .put("dictionaryFiles", JSONObject(dictionaryFiles)).put("dictionaryId", dictionaryId)
            .put("allowedRates", JSONObject().apply { allowedRates.forEach { (level, rates) -> put(level.name, JSONArray(rates.toList())) } })
            .put("confirmedProfiles", JSONArray(confirmedProfiles.toList()))
            .put("confirmedSettings", JSONArray(confirmedSettings.toList()))
            .put("confirmedPlanRows", JSONObject(confirmedPlanRows)).put("confirmedRequestRows", JSONObject(confirmedRequestRows))
            .put("plans", JSONObject().apply { plans.forEach { (key, plan) -> put(key, codec.encodeToString(SpeedPlan.serializer(), plan)) } })
            .put("expectedEdition", expectedEdition?.let { codec.encodeToString(DailyEdition.serializer(), it) } ?: JSONObject.NULL)
            .put("cleanupCompleted", cleanupCompleted)
        val bytes = ledger.toString(2).toByteArray(Charsets.UTF_8)
        val atomic = AtomicFile(File(output, "ownership-ledger.json"))
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
        report.put("privateOwnershipLedgerName", "ownership-ledger.json").put("privateOwnershipLedgerAvailable", true)
            .put("privateOwnershipLedgerSequence", ledgerSequence).put("privateOwnershipLedgerSha256", sha(bytes.toString(Charsets.UTF_8)))
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
        persistOwnership("fixture_inserted")
    }

    /** The row is owned only after actual UI controls and repository state confirm these bytes. */
    fun observeSettings(): AppSettings {
        val raw = requireNotNull(rows().getValue("preferences").singleOrNull { it[0] == "settings" }?.get(1))
        val value = codec.decodeFromString<AppSettings>(raw)
        val defaults = AppSettings()
        check(value.reader.sizeSp in 23f..25f)
        check(value == defaults.copy(reader = defaults.reader.copy(sizeSp = value.reader.sizeSp)))
        check(value.reader.lineHeight == 1.8f && value.reader.font == ReaderFont.SERIF)
        check(raw == codec.encodeToString(AppSettings.serializer(), value))
        check(runBlocking { app.repository.settings.first() } == value)
        confirmedSettings += raw
        report.put("confirmedSettingsSha256", sha(raw)).put("readerSizeSp", value.reader.sizeSp)
            .put("readerLineHeight", value.reader.lineHeight).put("readerFont", value.reader.font.name)
        persistOwnership("settings_confirmed")
        return value
    }

    /** Validate a real prepared plan and its retained fragment without accepting prefix-only ownership. */
    fun observePlan(plan: SpeedPlan) {
        check(plan.articleId == fixture.article.id && plan.bodyHash == fixture.body.contentHash)
        check(plan.granularity == SpeedGranularity.L3 && plan.paragraphIndices == listOf(0))
        check(plan.origin == "rules" && plan.questions.isEmpty() && plan.basePlanKey == null)
        check(plan.planKey == SpeedChunker.planKey(plan))
        SpeedChunker.validate(plan, fixture.text)
        check(runBlocking { requireNotNull(app.repository.speedReading).cachedPlan(plan.planKey) } == plan)
        val key = "speed-plan:${plan.planKey}"
        val raw = requireNotNull(rows().getValue("preferences").singleOrNull { it[0] == key }?.get(1))
        val record = codec.parseToJsonElement(raw).jsonObject
        check(record.keys == setOf("plan", "source", "sourceDigest"))
        check(codec.decodeFromJsonElement<SpeedPlan>(record.getValue("plan")) == plan)
        val source = record.getValue("source").jsonArray
        check(source.size == 1)
        val part = source.single().jsonObject
        check(part.keys == setOf("paragraph", "start", "text"))
        check(part.getValue("paragraph").jsonPrimitive.int == 0 && part.getValue("start").jsonPrimitive.int == 0)
        check(part.getValue("text").jsonPrimitive.content == fixture.text)
        check(record.getValue("sourceDigest").jsonPrimitive.content == sha(source.toString()))
        confirmedPlanRows[key] = raw
        plans[plan.planKey] = plan
        val requestKey = "speed-request:4:${sha(listOf(fixture.article.id, fixture.body.contentHash, "L3", 0, 1, false).joinToString("|"))}"
        val request = rows().getValue("preferences").singleOrNull { it[0] == requestKey }
        check(request?.get(1) == plan.planKey)
        confirmedRequestRows[requestKey] = plan.planKey
        report.put("ownedPlanKey", plan.planKey).put("planChunks", plan.chunks.size)
            .put("observedRequestProtocol", 4).put("ownedRequestKey", requestKey)
            .put("chunkRangesSha256", sha(plan.chunks.joinToString(";") { "${it.start},${it.end},${it.paragraph},${it.sentence},${it.wordCount}" }))
        persistOwnership("plan_and_request_confirmed")
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
            confirmedProfiles += raw
            receipt.put("sha256", sha(raw)).put("revision", profile.revision).put("granularity", profile.granularity.name)
                .put("rates", JSONObject(profile.rates.mapKeys { it.key.name }))
        } else check(setupActions == 0) { "Committed setup settings were not saved" }
        report.getJSONArray("profileStates").put(receipt)
        persistOwnership("profile_confirmed:$action")
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
        cleanupCompleted = true
        persistOwnership("preservation_confirmed")
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
                    id == "settings" -> original.getValue("preferences").none { it[0] == "settings" } && data == confirmedSettings.lastOrNull()
                    id.startsWith("speed-plan:") -> confirmedPlanRows[id] == data && plans.containsKey(id.removePrefix("speed-plan:"))
                    id.startsWith("speed-request:") -> {
                        confirmedRequestRows[id] == data && plans.containsKey(data)
                    }
                    !readerOpened -> false
                    id.startsWith("reading-library:session:") -> sessions[id.removePrefix("reading-library:session:")]?.let {
                        it.id == id.removePrefix("reading-library:session:") && it.articleId == f.article.id &&
                            it.startedAt in started..end && it.finishedAt in it.startedAt..end && it.totalParagraphs == 1 &&
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
        report.put("noTrainingRunsGenerated", added.getValue("preferences").none { it[0].orEmpty().startsWith("speed-library:") })
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
        val source = Source("speed-range:$run:source", "自写整块可见性试用", "https://example.invalid/speed-range/$run/feed",
            readingMode = ReadingMode.NATIVE, enabled = false, aiAllowed = false,
            license = "Self-authored fictional test prose, dedicated to the public domain under CC0.")
        val text = "In this fictional exercise, a group of readers studies the design of a neighbourhood garden. The writer describes how different choices can influence the daily experience of people who share the space. A careful reader needs to keep related words together while following the argument across several lines. The proposed entrance sits beside a narrow footpath where visitors can see the central planting beds. Along the western boundary, the committee plans to install comfortable wooden benches for neighbours who prefer a quiet place to rest. These details belong to an invented setting and do not describe a real construction project. Several members suggest that the opening hours should reflect the needs of local families. Others explain that a useful public space also depends on clear information and reliable maintenance. The discussion moves from the position of the entrance to the arrangement of shared facilities. A small covered area could provide shelter during a brief afternoon shower. Nearby tables would give visitors enough room to read a newspaper or discuss the next meeting. The group considers these possibilities without treating any single feature as a complete solution. Before reaching a decision, members compare the practical consequences of each proposal. One option creates a broad route through the centre, while another leaves more space for planting. People using a walking aid may value the wider path, especially when several visitors arrive together. Parents with young children may prefer a clear view across the garden from a shaded seat. The writer presents these preferences as questions for discussion rather than claims about measured benefits. Careful placement of the benches could preserve a clear path without isolating anyone from conversation. A notice near the entrance might explain who looks after the plants and how to report a broken fixture. The committee also considers whether a short printed guide would make the shared rules easier to understand. Long instructions can discourage readers, but an extremely brief notice may omit an important condition. The group therefore tries to keep each explanation both concise and complete. Later in the discussion, a volunteer suggests setting aside a modest area for seasonal experiments. Residents could compare several planting arrangements before choosing a permanent layout. Such an exercise would require a clear record of the changes and a realistic account of the effort involved. The proposal does not promise that every experiment will succeed or that every visitor will prefer the same arrangement. Instead, it gives the group a way to revisit a decision when new information becomes available. By the end of the meeting, the fictional committee has identified several questions that deserve further attention. Members have not approved a budget, started construction, or collected evidence of an improvement. The passage serves only as self-authored material for testing how a reading interface displays complete semantic groups. Its words remain unchanged when a reader selects a different group or moves through the paragraph. A complete group should remain visible whenever its measured height fits inside the available reading area."
        val article = Article("speed-range:$run:article", "speed-range:$run:guid", listOf(source.id),
            "整块可见性试用 · ${run.takeLast(8)}", "https://example.invalid/speed-range/$run/article", "自写长段阅读布局试用材料。",
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
