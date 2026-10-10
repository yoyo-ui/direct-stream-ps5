package com.directstream.ps5.data

import android.content.Context
import com.directstream.ps5.core.diag.Issue
import com.directstream.ps5.core.diag.SettingsValidator
import com.directstream.ps5.core.engine.EngineSettings
import com.directstream.ps5.core.ftp.FtpConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class ThemeMode { SYSTEM, DARK, LIGHT }

data class AppConfig(
    val host: String = "",
    val port: Int = 2121,
    val username: String = "",
    val connectTimeoutSec: Int = 15,
    val retries: Int = 3,
    val workers: Int = 4,
    val chunkMiB: Int = 8,
    val bufferMiB: Int = 64,
    val destinationDir: String = "/data/homebrew",
    val routingEnabled: Boolean = true,
    val overwriteByDefault: Boolean = false,
    val allowLanSources: Boolean = false,
    val maxParallelJobs: Int = 1,
    val payloadPort: Int = 9021,
    val deleteArchiveAfterExtract: Boolean = true,
    val notifications: Boolean = true,
    val autoResumeOnNetwork: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM
) {
    val configured get() = host.isNotBlank()

    fun engine() = EngineSettings(
        workers = workers, chunkSizeBytes = chunkMiB.toLong() shl 20, bufferBytes = bufferMiB.toLong() shl 20,
        maxRetries = retries
    )

    fun ftp(password: String) = FtpConfig(
        host = host.trim(), port = port, username = username, password = password,
        connectTimeoutMs = connectTimeoutSec * 1000, controlTimeoutMs = 30_000, dataTimeoutMs = 30_000
    )

    fun validate(): List<Issue> = SettingsValidator.validate(host, port, connectTimeoutSec, retries, engine(), destinationDir)
}

class SettingsRepository(private val context: Context, private val secrets: CredentialStore) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    val payloadFile: File get() = File(context.filesDir, "payload/unrar_ps5.elf")
    fun payloadImported() = payloadFile.isFile && payloadFile.length() > 0

    fun password(): String = secrets.get("ftp_password").orEmpty()
    fun setPassword(p: String) = secrets.put("ftp_password", p)

    private fun load() = AppConfig(
        host = prefs.getString("host", "") ?: "",
        port = prefs.getInt("port", 2121),
        username = prefs.getString("username", "") ?: "",
        connectTimeoutSec = prefs.getInt("timeout", 15),
        retries = prefs.getInt("retries", 3),
        workers = prefs.getInt("workers", 4),
        chunkMiB = prefs.getInt("chunk", 8),
        bufferMiB = prefs.getInt("buffer", 64),
        destinationDir = prefs.getString("dest", "/data/homebrew") ?: "/data/homebrew",
        routingEnabled = prefs.getBoolean("routing", true),
        overwriteByDefault = prefs.getBoolean("overwrite", false),
        allowLanSources = prefs.getBoolean("lan", false),
        maxParallelJobs = prefs.getInt("jobs", 1),
        payloadPort = prefs.getInt("payloadPort", 9021),
        deleteArchiveAfterExtract = prefs.getBoolean("delArchive", true),
        notifications = prefs.getBoolean("notify", true),
        autoResumeOnNetwork = prefs.getBoolean("autoResume", true),
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)
    )

    /** Validates first; only saves when there are no errors. Returns all issues (warnings included). */
    fun save(c: AppConfig): List<Issue> {
        val issues = c.validate()
        if (issues.none { it.severity == com.directstream.ps5.core.diag.Severity.ERROR }) {
            prefs.edit()
                .putString("host", c.host.trim()).putInt("port", c.port).putString("username", c.username)
                .putInt("timeout", c.connectTimeoutSec).putInt("retries", c.retries).putInt("workers", c.workers)
                .putInt("chunk", c.chunkMiB).putInt("buffer", c.bufferMiB).putString("dest", c.destinationDir)
                .putBoolean("routing", c.routingEnabled).putBoolean("overwrite", c.overwriteByDefault)
                .putBoolean("lan", c.allowLanSources).putInt("jobs", c.maxParallelJobs.coerceIn(1, 3))
                .putInt("payloadPort", c.payloadPort).putBoolean("delArchive", c.deleteArchiveAfterExtract)
                .putBoolean("notify", c.notifications).putBoolean("autoResume", c.autoResumeOnNetwork)
                .putString("theme", c.theme.name).apply()
            _config.value = c
        }
        return issues
    }

    fun importPayload(bytes: ByteArray): Boolean {
        if (bytes.size < 64 || bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() || bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()) return false
        payloadFile.parentFile?.mkdirs(); payloadFile.writeBytes(bytes); return true
    }
    fun removePayload() { payloadFile.delete() }
}
