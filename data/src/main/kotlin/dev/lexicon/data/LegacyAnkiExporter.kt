package dev.lexicon.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.lexicon.domain.AnkiExporter
import dev.lexicon.domain.Card
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Legacy Anki v11 package. Notes have stable IDs/GUIDs; all exported cards are new, without review history.
 * Format references: Anki's rslib/src/import_export/package and genanki package.py (MIT).
 */
class LegacyAnkiExporter : AnkiExporter {
    override suspend fun export(cards: List<Card>, destination: File): File = withContext(Dispatchers.IO) {
        require(cards.isNotEmpty()) { "还没有可导出的生词卡" }
        require(cards.map { it.noteGuid }.distinct().size == cards.size) { "卡片标识重复，无法导出" }
        destination.parentFile?.mkdirs()
        val directory = File(destination.parentFile, "anki-${UUID.randomUUID()}").apply { mkdirs() }
        val database = File(directory, "collection.anki2")
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            writeDatabase(cards, database)
            temporary.outputStream().use { file ->
                ZipOutputStream(file).use { zip ->
                    zip.putNextEntry(ZipEntry("collection.anki2")); database.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    zip.putNextEntry(ZipEntry("media")); zip.write("{}".toByteArray()); zip.closeEntry()
                }
            }
            try { Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            destination
        } finally { directory.deleteRecursively(); temporary.delete() }
    }

    private fun writeDatabase(cards: List<Card>, file: File) {
        BundledSQLiteDriver().open(file.absolutePath).use { db ->
            db.exec("PRAGMA journal_mode=DELETE")
            db.exec("PRAGMA page_size=4096")
            schema.forEach(db::exec)
            val now = System.currentTimeMillis()
            val models = buildJsonObject { put(MODEL_ID.toString(), model()) }
            val decks = buildJsonObject { put("1", deck(1, "Default")); put(DECK_ID.toString(), deck(DECK_ID, "Lexicon IDE::Vocabulary")) }
            val conf = buildJsonObject { put("nextPos", cards.size + 1); put("curDeck", DECK_ID); put("curModel", MODEL_ID.toString()); putJsonArray("activeDecks") { add(DECK_ID) } }
            val deckConf = buildJsonObject { put("1", defaultDeckConfig()) }
            db.prepare("INSERT INTO col VALUES(1,?,?,?,?,?,?,?,?,?,?,?,?)").use { s ->
                s.bindLong(1, now / 1000); s.bindLong(2, now); s.bindLong(3, SCHEMA_TIME); s.bindLong(4, 11)
                s.bindLong(5, 0); s.bindLong(6, -1); s.bindLong(7, 0); s.bindText(8, conf.toString())
                s.bindText(9, models.toString()); s.bindText(10, decks.toString()); s.bindText(11, deckConf.toString()); s.bindText(12, "{}")
                s.step()
            }
            db.exec("BEGIN IMMEDIATE TRANSACTION")
            try {
                db.prepare("INSERT INTO notes VALUES(?,?,?,?,?,?,?,?,?,?,?)").use { notes ->
                    db.prepare("INSERT INTO cards VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { cardStmt ->
                        cards.forEachIndexed { index, card ->
                            val nid = stableId("note:${card.id}")
                            val noteFields = fields(card)
                            val modified = card.updatedAt / 1000
                            notes.bindLong(1, nid); notes.bindText(2, card.noteGuid); notes.bindLong(3, MODEL_ID)
                            notes.bindLong(4, modified); notes.bindLong(5, -1); notes.bindText(6, " ${tags(card).joinToString(" ")} ")
                            notes.bindText(7, noteFields.joinToString("\u001f")); notes.bindText(8, card.content.headword)
                            notes.bindLong(9, checksum(card.content.headword)); notes.bindLong(10, 0); notes.bindText(11, "")
                            notes.step(); notes.reset(); notes.clearBindings()
                            val values = listOf(stableId("card:${card.id}"), nid, DECK_ID, 0L, modified, -1L, 0L, 0L,
                                index.toLong() + 1, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)
                            values.forEachIndexed { i, v -> cardStmt.bindLong(i + 1, v) }
                            cardStmt.bindText(18, ""); cardStmt.step(); cardStmt.reset(); cardStmt.clearBindings()
                        }
                    }
                }
                db.exec("COMMIT")
            } catch (e: Throwable) { db.exec("ROLLBACK"); throw e }
            db.exec("CREATE INDEX ix_notes_usn ON notes (usn)")
            db.exec("CREATE INDEX ix_cards_usn ON cards (usn)")
            db.exec("CREATE INDEX ix_cards_nid ON cards (nid)")
            db.exec("CREATE INDEX ix_cards_sched ON cards (did, queue, due)")
            db.exec("CREATE INDEX ix_notes_csum ON notes (csum)")
        }
    }

    companion object {
        const val MODEL_ID = 1793145600101L
        const val DECK_ID = 1793145600102L
        const val SCHEMA_TIME = 1793145600000L
        internal fun stableId(value: String): Long = sha256(value).take(15).toLong(16)
        internal fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;").replace("\n", "<br>")
        internal fun tags(card: Card): List<String> = listOfNotNull(
            "LexiconIDE", card.topic?.let { "topic::" + it.replace(Regex("\\s+"), "_") },
            "exam::${card.content.examRelevance}", "register::${card.content.registerTag}",
        )
        internal fun fields(card: Card): List<String> {
            val content = card.content
            val back = buildString {
                append("<p>").append(escape(content.contextDefinition)).append("</p>")
                append("<p><b>搭配 · AI 例示</b></p><ul>")
                content.collocations.forEach { append("<li><b>").append(escape(it.phrase)).append("</b><br>").append(escape(it.example)).append("</li>") }
                append("</ul><p><b>Paraphrase</b><br>").append(escape(content.paraphrase)).append("</p>")
                append("<p>").append(escape(content.registerTag)).append(" · exam: ").append(escape(content.examRelevance)).append("</p>")
            }
            val context = buildString {
                append("<blockquote>").append(escape(card.sourceSentence)).append("</blockquote><small>")
                append(when (card.contextOrigin) { dev.lexicon.domain.ContextOrigin.USER_SUPPLIED -> "用户补充语境 · "; dev.lexicon.domain.ContextOrigin.AI_GENERATED -> "AI 改写语境 · "; else -> "原文语境 · " })
                append(escape(card.sourceRef.display()))
                card.sourceRef.publishedAt?.let { append(" · ").append(DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(it))) }
                card.sourceRef.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let {
                    append(" · <a href=\"").append(escape(it)).append("\">原文</a>")
                }
                append("</small>")
            }
            return listOf(escape(content.headword), back, context, tags(card).joinToString(" "))
        }
        private fun checksum(text: String): Long = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).take(4).joinToString("") { "%02x".format(it) }.toLong(16)
        private fun model() = buildJsonObject {
            put("id", MODEL_ID); put("name", "Lexicon IDE Vocabulary"); put("type", 0); put("mod", SCHEMA_TIME / 1000)
            put("usn", -1); put("sortf", 0); put("did", DECK_ID)
            putJsonArray("flds") { listOf("Front", "Back", "Context", "Tags").forEachIndexed { index, name -> add(buildJsonObject {
                put("name", name); put("ord", index); put("sticky", false); put("rtl", false); put("font", "Arial"); put("size", 20); putJsonArray("media") {}
            }) } }
            putJsonArray("tmpls") { add(buildJsonObject {
                put("name", "Vocabulary"); put("ord", 0); put("qfmt", "{{Front}}")
                put("afmt", "{{FrontSide}}<hr id=answer>{{Back}}<hr>{{Context}}")
                put("bqfmt", ""); put("bafmt", ""); put("did", JsonNull)
            }) }
            put("css", ".card { font-family: sans-serif; font-size:20px; text-align:left; line-height:1.6; color:#28313a; background:#f7f5ef; padding:16px; } .nightMode.card {color:#ddd9e5; background:#242330;} small {font-size:14px;} blockquote {margin:12px 0; padding-left:12px; border-left:2px solid #8196bd;}")
            put("latexPre", "\\documentclass[12pt]{article}\n\\pagestyle{empty}\n\\begin{document}")
            put("latexPost", "\\end{document}"); put("latexsvg", false)
            putJsonArray("req") { add(buildJsonArray { add(0); add("all"); add(buildJsonArray { add(0) }) }) }
            putJsonArray("tags") {}; putJsonArray("vers") {}
        }
        private fun deck(id: Long, name: String) = buildJsonObject {
            put("id", id); put("name", name); put("mod", SCHEMA_TIME / 1000); put("usn", -1); put("desc", "单向内容快照；不含 Lexicon IDE 复习进度")
            put("dyn", 0); put("conf", 1); put("collapsed", false); put("browserCollapsed", false); put("extendNew", 10); put("extendRev", 50)
            listOf("newToday", "revToday", "lrnToday", "timeToday").forEach { key -> putJsonArray(key) { add(0); add(0) } }
        }
        private fun defaultDeckConfig() = buildJsonObject {
            put("id", 1); put("name", "Default"); put("mod", 0); put("usn", 0); put("maxTaken", 60); put("timer", 0)
            put("autoplay", true); put("replayq", true); put("dyn", false)
            putJsonObject("new") {
                putJsonArray("delays") { add(1); add(10) }; putJsonArray("ints") { add(1); add(4) }
                put("initialFactor", 2500); put("order", 1); put("perDay", 20); put("bury", false); put("separate", true)
            }
            putJsonObject("rev") { put("perDay", 200); put("ease4", 1.3); put("fuzz", 0.05); put("ivlFct", 1); put("maxIvl", 36500); put("bury", false); put("hardFactor", 1.2) }
            putJsonObject("lapse") { putJsonArray("delays") { add(10) }; put("mult", 0); put("minInt", 1); put("leechFails", 8); put("leechAction", 0) }
        }
        private val schema = listOf(
            "CREATE TABLE col (id integer PRIMARY KEY, crt integer NOT NULL, mod integer NOT NULL, scm integer NOT NULL, ver integer NOT NULL, dty integer NOT NULL, usn integer NOT NULL, ls integer NOT NULL, conf text NOT NULL, models text NOT NULL, decks text NOT NULL, dconf text NOT NULL, tags text NOT NULL)",
            "CREATE TABLE notes (id integer PRIMARY KEY, guid text NOT NULL, mid integer NOT NULL, mod integer NOT NULL, usn integer NOT NULL, tags text NOT NULL, flds text NOT NULL, sfld integer NOT NULL, csum integer NOT NULL, flags integer NOT NULL, data text NOT NULL)",
            "CREATE TABLE cards (id integer PRIMARY KEY, nid integer NOT NULL, did integer NOT NULL, ord integer NOT NULL, mod integer NOT NULL, usn integer NOT NULL, type integer NOT NULL, queue integer NOT NULL, due integer NOT NULL, ivl integer NOT NULL, factor integer NOT NULL, reps integer NOT NULL, lapses integer NOT NULL, left integer NOT NULL, odue integer NOT NULL, odid integer NOT NULL, flags integer NOT NULL, data text NOT NULL)",
            "CREATE TABLE revlog (id integer PRIMARY KEY, cid integer NOT NULL, usn integer NOT NULL, ease integer NOT NULL, ivl integer NOT NULL, lastIvl integer NOT NULL, factor integer NOT NULL, time integer NOT NULL, type integer NOT NULL)",
            "CREATE TABLE graves (usn integer NOT NULL, oid integer NOT NULL, type integer NOT NULL)",
        )
    }
}

internal fun SQLiteConnection.exec(sql: String) { prepare(sql).use { it.step() } }
