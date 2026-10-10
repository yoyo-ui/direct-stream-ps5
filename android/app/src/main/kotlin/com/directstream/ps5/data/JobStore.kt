package com.directstream.ps5.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.directstream.ps5.core.engine.JobState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Only metadata is persisted: never file contents. Secret-bearing fields are stored encrypted. */
data class JobRecord(
    val id: String,
    val remote: Boolean,
    val displayName: String,
    val sourceLabel: String,          // redacted, safe to show
    val urlEnc: String? = null,       // encrypted full URL (may hold tokens)
    val headersEnc: String? = null,   // encrypted "Name: value" lines
    val localUri: String? = null,
    val fileName: String? = null,
    val destDir: String,
    val overwrite: Boolean = false,
    val strategy: String = "SEND_ARCHIVE",
    val passwordEnc: String? = null,
    val extractDir: String? = null,
    val state: JobState = JobState.QUEUED,
    val bytesDone: Long = 0,
    val total: Long? = null,
    val error: String? = null,
    val errorKind: String? = null,
    val recoverable: Boolean = false,
    val detail: String? = null,
    val verification: String? = null,
    val mode: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val length: Long? = null,
    val identity: String? = null,
    val rangesOk: Boolean = false,
    val seekable: Boolean = false,
    val partPath: String? = null,
    val finalPath: String? = null,
    val avgBps: Double? = null,
    val restartRequired: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val hasValidator get() = !etag.isNullOrEmpty() || !lastModified.isNullOrEmpty()
    val finished get() = state == JobState.COMPLETED || state == JobState.FAILED || state == JobState.CANCELLED
    val active get() = state in setOf(JobState.PREPARING, JobState.TRANSFERRING, JobState.VERIFYING, JobState.FINALIZING)

    fun toJson(): String = JSONObject().apply {
        put("remote", remote); put("displayName", displayName); put("sourceLabel", sourceLabel)
        put("urlEnc", urlEnc); put("headersEnc", headersEnc); put("localUri", localUri); put("fileName", fileName)
        put("destDir", destDir); put("overwrite", overwrite); put("strategy", strategy); put("passwordEnc", passwordEnc)
        put("extractDir", extractDir); put("bytesDone", bytesDone); put("total", total); put("error", error)
        put("errorKind", errorKind); put("recoverable", recoverable); put("detail", detail); put("verification", verification)
        put("mode", mode); put("etag", etag); put("lastModified", lastModified); put("length", length)
        put("identity", identity); put("rangesOk", rangesOk); put("seekable", seekable); put("partPath", partPath)
        put("finalPath", finalPath); put("avgBps", avgBps); put("restartRequired", restartRequired)
    }.toString()

    companion object {
        private fun JSONObject.str(k: String) = if (isNull(k)) null else getString(k)
        private fun JSONObject.lng(k: String) = if (isNull(k)) null else getLong(k)

        fun fromRow(id: String, state: String, created: Long, updated: Long, json: String): JobRecord {
            val o = JSONObject(json)
            return JobRecord(
                id = id, remote = o.getBoolean("remote"), displayName = o.getString("displayName"), sourceLabel = o.getString("sourceLabel"),
                urlEnc = o.str("urlEnc"), headersEnc = o.str("headersEnc"), localUri = o.str("localUri"), fileName = o.str("fileName"),
                destDir = o.getString("destDir"), overwrite = o.optBoolean("overwrite"), strategy = o.optString("strategy", "SEND_ARCHIVE"),
                passwordEnc = o.str("passwordEnc"), extractDir = o.str("extractDir"),
                state = runCatching { JobState.valueOf(state) }.getOrDefault(JobState.FAILED),
                bytesDone = o.optLong("bytesDone"), total = o.lng("total"), error = o.str("error"), errorKind = o.str("errorKind"),
                recoverable = o.optBoolean("recoverable"), detail = o.str("detail"), verification = o.str("verification"), mode = o.str("mode"),
                etag = o.str("etag"), lastModified = o.str("lastModified"), length = o.lng("length"), identity = o.str("identity"),
                rangesOk = o.optBoolean("rangesOk"), seekable = o.optBoolean("seekable"), partPath = o.str("partPath"), finalPath = o.str("finalPath"),
                avgBps = if (o.isNull("avgBps")) null else o.getDouble("avgBps"), restartRequired = o.optBoolean("restartRequired"),
                createdAt = created, updatedAt = updated
            )
        }
    }
}

class JobStore(context: Context) : SQLiteOpenHelper(context, "jobs.db", null, 1) {
    private val _jobs = MutableStateFlow<List<JobRecord>>(emptyList())
    val jobs: StateFlow<List<JobRecord>> = _jobs.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE jobs (id TEXT PRIMARY KEY, state TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, data TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    private fun readAll(): List<JobRecord> = readableDatabase.rawQuery("SELECT id,state,created,updated,data FROM jobs ORDER BY created DESC", null).use { c ->
        buildList { while (c.moveToNext()) runCatching { add(JobRecord.fromRow(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getString(4))) } }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) { _jobs.value = readAll() }

    suspend fun upsert(r: JobRecord) = withContext(Dispatchers.IO) {
        val v = ContentValues().apply {
            put("id", r.id); put("state", r.state.name); put("created", r.createdAt)
            put("updated", System.currentTimeMillis()); put("data", r.toJson())
        }
        writableDatabase.insertWithOnConflict("jobs", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        _jobs.value = readAll()
    }

    suspend fun delete(ids: Collection<String>) = withContext(Dispatchers.IO) {
        ids.forEach { writableDatabase.delete("jobs", "id=?", arrayOf(it)) }
        _jobs.value = readAll()
    }

    fun get(id: String): JobRecord? = _jobs.value.firstOrNull { it.id == id }
}
