package dev.lexicon.ide.ui

import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One opt-in cleanup after a host comparison proved this is the sole added trial row.
 * Does not launch an Activity, restore a database, or alter any existing learning row.
 */
@RunWith(AndroidJUnit4::class)
class NavigationTrialCleanupTest {
    @Test fun removeOnlyTheHostVerifiedInitialHomeEdition() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("dailyEditionCleanup") == "1")
        val day = requireNotNull(args.getString("day")).also { require(it.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) }
        val expected = requireNotNull(args.getString("expectedHash")).also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
        val output = File(context.filesDir, "validation-1.22.0/initial-edition-cleanup.json")
        check(!output.exists())
        val key = "reading-library:edition:$day"
        SQLiteDatabase.openDatabase(context.getDatabasePath("lexicon.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                val before = rows(db)
                val row = before.getValue("preferences").single { it[0] == key }
                val data = requireNotNull(row[1])
                check(hash(data) == expected)
                check(JSONObject(data).getString("day") == day)
                check(db.delete("preferences", "`key`=? AND data=?", arrayOf(key, data)) == 1)
                val after = rows(db)
                val expectedRows = before.toMutableMap().apply { put("preferences", before.getValue("preferences").filterNot { it[0] == key }) }
                check(after == expectedRows)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        output.parentFile?.mkdirs()
        output.writeText(JSONObject().put("status", "passed").put("day", day).put("removedRows", 1)
            .put("dataSha256", expected).put("allOtherRowsUnchanged", true)
            .put("authorizationEvidence", "Host pre-iteration comparison found this daily edition as the sole new row; no original row was changed or removed")
            .toString(2))
    }

    private fun rows(db: SQLiteDatabase): Map<String, List<List<String?>>> {
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return tables.associateWith { table ->
            val quoted = "\"" + table.replace("\"", "\"\"") + "\""
            db.rawQuery("SELECT * FROM $quoted ORDER BY 1", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
            }
        }
    }
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
