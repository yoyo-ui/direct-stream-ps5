package com.directstream.ps5.core.diag

import com.directstream.ps5.core.ftp.*
import com.directstream.ps5.core.http.HttpSource
import com.directstream.ps5.core.http.SourceException
import com.directstream.ps5.core.net.UrlRejectedException
import com.directstream.ps5.core.util.Names
import java.net.InetAddress
import java.net.URL
import java.util.UUID

enum class StepStatus { PASS, FAIL, WARN, SKIPPED }
data class DiagStep(val name: String, val status: StepStatus, val detail: String, val durationMs: Long)
data class BenchResult(val bytes: Long, val durationMs: Long) {
    val bytesPerSecond: Double get() = if (durationMs <= 0) 0.0 else bytes * 1000.0 / durationMs
    val megabytesPerSecond: Double get() = bytesPerSecond / 1e6
}

/** Real checks against the real console and network. Nothing here reports success it did not observe. */
class Diagnostics(private val ftpConfig: FtpConfig, private val http: HttpSource) {

    private inline fun step(name: String, block: () -> Pair<StepStatus, String>): DiagStep {
        val t0 = System.nanoTime()
        val (st, d) = try { block() } catch (e: FtpAuthException) { StepStatus.FAIL to "Login rejected: ${e.message}" }
        catch (e: Exception) { StepStatus.FAIL to (e.message ?: e::class.java.simpleName) }
        return DiagStep(name, st, d, (System.nanoTime() - t0) / 1_000_000)
    }

    /** Connect, log in, list, and (optionally) prove write access with a tiny temp file that is deleted again. */
    fun testConsole(listDir: String, writeTestDir: String?): List<DiagStep> {
        val steps = mutableListOf<DiagStep>()
        val ftp = FtpClient(ftpConfig)
        try {
            steps += step("Connect to ${ftpConfig.host}:${ftpConfig.port}") { ftp.connect(); StepStatus.PASS to "Server says: ${ftp.banner.take(80)}" }
            if (!ftp.isConnected) return steps
            steps += step("FTP login") { ftp.login(); StepStatus.PASS to (if (ftpConfig.username.isBlank()) "Logged in anonymously" else "Logged in as ${ftpConfig.username}") }
            if (steps.last().status != StepStatus.PASS) return steps
            steps += step("Server capabilities") {
                val f = ftp.features
                StepStatus.PASS to if (f.isEmpty()) "FEAT not supported (resume and listing will use conservative defaults)"
                else "SIZE=${ftp.supports("SIZE")} REST=${ftp.supports("REST")} MLSD=${ftp.supports("MLSD")} EPSV=${ftp.supports("EPSV")}"
            }
            steps += step("List $listDir") {
                val n = ftp.list(Names.normalizeRemoteDir(listDir)).size; StepStatus.PASS to "$n entries"
            }
            if (writeTestDir == null) steps += DiagStep("Write permission", StepStatus.SKIPPED, "No destination chosen", 0)
            else steps += step("Write permission in $writeTestDir") {
                val dir = Names.normalizeRemoteDir(writeTestDir)
                if (!ftp.directoryExists(dir)) return@step StepStatus.WARN to "Directory does not exist yet; it will be created on the first transfer if the console allows it."
                val probe = Names.joinRemote(dir, ".directstream-probe-${UUID.randomUUID().toString().take(8)}.tmp")
                val up = ftp.beginStore(probe); up.out.write(ByteArray(16) { it.toByte() }); up.finish()
                val size = ftp.size(probe)
                ftp.delete(probe)
                StepStatus.PASS to "Created and removed a 16-byte test file" + if (size is SizeResult.Found && size.bytes != 16L) " (size mismatch: ${size.bytes})" else ""
            }
        } finally { ftp.quit(); ftp.close() }
        return steps
    }

    fun testSource(url: String, headers: Map<String, String> = emptyMap()): List<DiagStep> {
        val steps = mutableListOf<DiagStep>()
        val host = runCatching { URL(url).host }.getOrNull()
        if (host != null) steps += step("DNS lookup $host") {
            val a = InetAddress.getAllByName(host); StepStatus.PASS to a.joinToString { it.hostAddress ?: "?" }
        }
        var info: com.directstream.ps5.core.http.SourceInfo? = null
        steps += step("HTTP preflight") {
            info = http.preflight(url, headers)
            StepStatus.PASS to "HTTP ${info!!.status}, ${info!!.contentLength?.let { "$it bytes" } ?: "size unknown"}, file '${info!!.fileName}'"
        }
        info?.let { i ->
            steps += DiagStep("Range requests", if (i.acceptsRanges) StepStatus.PASS else StepStatus.WARN,
                if (i.acceptsRanges) "Supported: parallel download and resume are possible" else "Not supported: single stream only, no resume", 0)
            steps += DiagStep("Resume validator", if (i.hasResumeValidator) StepStatus.PASS else StepStatus.WARN,
                if (i.hasResumeValidator) "ETag/Last-Modified available" else "None: an interrupted transfer would restart from zero", 0)
        }
        return steps
    }

    /** Local-memory-to-console benchmark: uploads [bytes] of generated data to a temp file, then deletes it. */
    fun benchmarkUpload(dir: String, bytes: Long = 32L shl 20): BenchResult {
        val ftp = FtpClient(ftpConfig)
        try {
            ftp.connect(); ftp.login()
            val d = Names.normalizeRemoteDir(dir); ftp.makeDirectories(d)
            val probe = Names.joinRemote(d, ".directstream-bench-${UUID.randomUUID().toString().take(8)}.tmp")
            val block = ByteArray(256 * 1024) { (it * 31).toByte() }
            val t0 = System.nanoTime()
            val up = ftp.beginStore(probe)
            try {
                var left = bytes
                while (left > 0) { val n = minOf(left, block.size.toLong()).toInt(); up.out.write(block, 0, n); left -= n }
                up.finish()
            } catch (e: Exception) { up.abort(); throw e }
            val ms = (System.nanoTime() - t0) / 1_000_000
            runCatching { ftp.delete(probe) }
            return BenchResult(bytes, ms)
        } finally { ftp.quit(); ftp.close() }
    }

    /** Remote-source-to-console benchmark: streams the first [bytes] of the URL into a temp file on the console. */
    fun benchmarkRemote(url: String, headers: Map<String, String>, dir: String, bytes: Long = 32L shl 20): BenchResult {
        val info = http.preflight(url, headers)
        val want = minOf(bytes, info.contentLength ?: bytes)
        val ftp = FtpClient(ftpConfig)
        try {
            ftp.connect(); ftp.login()
            val d = Names.normalizeRemoteDir(dir); ftp.makeDirectories(d)
            val probe = Names.joinRemote(d, ".directstream-bench-${UUID.randomUUID().toString().take(8)}.tmp")
            val t0 = System.nanoTime()
            val body = if (info.acceptsRanges) http.openRange(info, headers, 0, want - 1) else http.openFull(info, headers)
            val up = ftp.beginStore(probe)
            var moved = 0L
            try {
                body.use { b ->
                    val buf = ByteArray(256 * 1024)
                    while (moved < want) {
                        val n = b.input.read(buf, 0, minOf(buf.size.toLong(), want - moved).toInt()); if (n < 0) break
                        up.out.write(buf, 0, n); moved += n
                    }
                }
                up.finish()
            } catch (e: Exception) { up.abort(); throw e }
            val ms = (System.nanoTime() - t0) / 1_000_000
            runCatching { ftp.delete(probe) }
            return BenchResult(moved, ms)
        } finally { ftp.quit(); ftp.close() }
    }

    companion object {
        /** Plain-language advice from measured numbers only. Returns an empty list when nothing was measured. */
        fun recommend(upload: BenchResult?, remote: BenchResult?, rangesSupported: Boolean?): List<String> {
            val out = mutableListOf<String>()
            if (upload != null && upload.durationMs > 0) {
                val mb = upload.megabytesPerSecond
                out += "Phone → console upload measured ${"%.1f".format(mb)} MB/s."
                if (mb < 5) out += "That is slow for a LAN. Check Wi-Fi signal/band (5 GHz), or try the Conservative preset."
            }
            if (remote != null && remote.durationMs > 0 && upload != null) {
                val r = remote.megabytesPerSecond; val u = upload.megabytesPerSecond
                out += "Internet source → console measured ${"%.1f".format(r)} MB/s."
                if (r < u * 0.6) out += "The download side is the bottleneck. Parallel ranges may help if the host allows them."
                else out += "The console link is the bottleneck; more download workers won't make it faster."
            }
            if (rangesSupported == false) out += "This host does not support range requests, so transfers use one stream and can't be resumed."
            return out
        }
    }
}
