package com.directstream.ps5.core.diag

import com.directstream.ps5.core.engine.EngineSettings
import com.directstream.ps5.core.net.UrlPolicy
import com.directstream.ps5.core.util.Names
import java.net.InetAddress

enum class Severity { ERROR, WARNING }
data class Issue(val field: String, val message: String, val severity: Severity = Severity.ERROR)

object Presets {
    val CONSERVATIVE = EngineSettings(workers = 2, chunkSizeBytes = 4L shl 20, bufferBytes = 32L shl 20, maxRetries = 5, retryBaseDelayMs = 2_000)
    val BALANCED = EngineSettings(workers = 4, chunkSizeBytes = 8L shl 20, bufferBytes = 64L shl 20, maxRetries = 3)
    val TURBO = EngineSettings(workers = 8, chunkSizeBytes = 8L shl 20, bufferBytes = 128L shl 20, maxRetries = 3)

    /**
     * Never let one transfer ask for more RAM than is sensible on this device.
     * [maxAppMemoryBytes] is typically a fraction of Runtime.maxMemory(). Returns the adjusted settings and the reason.
     */
    fun clampToDevice(s: EngineSettings, maxAppMemoryBytes: Long): Pair<EngineSettings, String?> {
        val cap = (maxAppMemoryBytes / 2).coerceAtLeast(8L shl 20)
        var out = s.sanitized(); var why: String? = null
        if (out.bufferBytes > cap) { out = out.copy(bufferBytes = cap); why = "Buffer reduced to ${cap shr 20} MiB to fit this device's memory." }
        val window = out.bufferBytes / out.chunkSizeBytes
        if (window < 2 && out.workers > 1) {
            out = out.copy(chunkSizeBytes = (out.bufferBytes / 2).coerceAtLeast(256L shl 10))
            why = (why ?: "") + " Chunk size reduced so at least two chunks fit in the buffer."
        }
        if (out.workers > window.coerceAtLeast(2)) out = out.copy(workers = window.toInt().coerceAtLeast(1))
        return out to why?.trim()
    }
}

object SettingsValidator {
    private val HOSTNAME = Regex("""^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)(\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""")
    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun validateHost(host: String): List<Issue> {
        val h = host.trim()
        if (h.isEmpty()) return listOf(Issue("host", "Enter your PS5's IP address (see the console's network settings)."))
        if (IPV4.matches(h)) {
            val parts = h.split('.').map { it.toInt() }
            if (parts.any { it > 255 }) return listOf(Issue("host", "Not a valid IP address."))
            val addr = InetAddress.getByName(h)
            if (addr.isAnyLocalAddress) return listOf(Issue("host", "0.0.0.0 is not a console address."))
            if (!UrlPolicy.isPrivateAddress(addr))
                return listOf(Issue("host", "That is a public internet address. FTP is unencrypted; use your console's local-network address."))
            return emptyList()
        }
        if (!HOSTNAME.matches(h)) return listOf(Issue("host", "Enter an IPv4 address or a host name."))
        return listOf(Issue("host", "Host names are resolved when connecting; make sure it points to a device on your local network.", Severity.WARNING))
    }

    fun validate(
        host: String, port: Int, connectTimeoutSec: Int, retries: Int, engine: EngineSettings, destinationDir: String
    ): List<Issue> {
        val out = validateHost(host).toMutableList()
        if (port !in 1..65535) out += Issue("port", "Port must be between 1 and 65535.")
        if (connectTimeoutSec !in 1..120) out += Issue("timeout", "Timeout must be 1–120 seconds.")
        if (retries !in 0..20) out += Issue("retries", "Retries must be 0–20.")
        if (engine.workers !in 1..16) out += Issue("workers", "Workers must be 1–16.")
        if (engine.chunkSizeBytes !in (256L shl 10)..(64L shl 20)) out += Issue("chunk", "Chunk size must be 256 KiB–64 MiB.")
        if (engine.bufferBytes !in (2L shl 20)..(1024L shl 20)) out += Issue("buffer", "Buffer must be 2–1024 MiB.")
        try { Names.normalizeRemoteDir(destinationDir) } catch (e: IllegalArgumentException) { out += Issue("destination", e.message ?: "Invalid directory") }
        if (engine.workers > 1 && engine.bufferBytes / engine.chunkSizeBytes < 2)
            out += Issue("buffer", "Buffer should hold at least two chunks, otherwise parallel mode falls back to one stream.", Severity.WARNING)
        return out
    }
}
