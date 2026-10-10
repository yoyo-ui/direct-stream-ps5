package com.directstream.ps5.core.console

import com.directstream.ps5.core.ftp.FtpClient
import com.directstream.ps5.core.ftp.FtpConfig
import com.directstream.ps5.core.ftp.SizeResult
import com.directstream.ps5.core.util.Names
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Port of the original project's "Extract on PS5" protocol (transfer_core.py, v2.9.0):
 *   1. the archive has already been uploaded to /data/unrar/<name> over FTP (by TransferEngine);
 *   2. write /data/unrar/config.ini (format of bizkut/unrar-ps5);
 *   3. send the user-supplied unrar_ps5.elf, raw, to the console's payload-loader TCP port (default 9021);
 *   4. poll /data/unrar/unrar.log over FTP until it reports success or failure.
 * This phone never executes the payload: it only sends bytes to the console's own loader.
 * The payload is NOT bundled; the user must supply it. Only the configured console host is ever contacted.
 * Verified against a mock console only; it has not been exercised on real hardware.
 */
object UnrarConfig {
    const val DIR = "/data/unrar"
    const val CONFIG_PATH = "/data/unrar/config.ini"
    const val LOG_PATH = "/data/unrar/unrar.log"
    const val DEFAULT_PAYLOAD_PORT = 9021

    fun generate(
        filename: String, archiveLocation: String = DIR, extractLocation: String = "/data/homebrew",
        password: String? = null, deleteAfter: Boolean = true, progress: Int = 10, threads: Int = 0, nice: Int = -20, cpuMask: Int = 0
    ): String {
        listOf(filename, archiveLocation, extractLocation, password ?: "").forEach { Names.requireSafeFtpArgument(it) }
        return listOf(
            "filename=$filename", "archive_location=$archiveLocation", "archive_password=${password ?: ""}",
            "delete_after=${if (deleteAfter) 1 else 0}", "extract_location=$extractLocation", "threads=$threads",
            "nice=$nice", "cpu_mask=$cpuMask", "progress=${progress.coerceIn(1, 100)}", ""
        ).joinToString("\n")
    }
}

sealed class UnrarEvent {
    data class Done(val titleId: String?, val finalPath: String?) : UnrarEvent()
    data class Result(val code: Int, val reason: String) : UnrarEvent() { val ok get() = code == 0 || reason == "success" }
    object Skipped : UnrarEvent()
    object NormalizeError : UnrarEvent()
    data class Failed(val line: String) : UnrarEvent()
}

object UnrarLog {
    private val HARD_ERRORS = listOf("archive_error", "extract_error", "sidecar_config_error", "config_error", "UnRAR error:")
    fun parseLine(line: String): UnrarEvent? {
        if (line.startsWith("done archive=")) return UnrarEvent.Done(
            Regex("""title_id=([^\s]+)""").find(line)?.groupValues?.get(1), Regex("""final_path=([^\s]+)""").find(line)?.groupValues?.get(1))
        if ("extract_result" in line) {
            val code = Regex("""code=(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val reason = Regex("""reason=([^\s]+)""").find(line)?.groupValues?.get(1) ?: "unknown"
            return UnrarEvent.Result(code, reason)
        }
        if (line.startsWith("skip archive=")) return UnrarEvent.Skipped
        if (line.startsWith("normalize_error")) return UnrarEvent.NormalizeError
        if (HARD_ERRORS.any { line.startsWith(it) }) return UnrarEvent.Failed(line)
        return null
    }
}

class ConsoleExtractionException(message: String) : IOException(message)

sealed class ExtractionResult {
    data class Success(val titleId: String?, val finalPath: String?, val detail: String) : ExtractionResult()
    data class Failure(val reason: String) : ExtractionResult()
}

class ConsoleExtractor(private val ftpConfig: FtpConfig, private val log: (String) -> Unit = {}) {

    /** Rejects anything that isn't an ELF image, so a wrong file can't be sent to the loader by mistake. */
    fun validatePayload(bytes: ByteArray) {
        if (bytes.size < 64 || bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() || bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte())
            throw ConsoleExtractionException("That file is not an ELF payload.")
    }

    fun sendPayload(port: Int, payload: ByteArray, timeoutMs: Int = 10_000) {
        validatePayload(payload)
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ftpConfig.host, port), timeoutMs)
                s.soTimeout = timeoutMs
                s.getOutputStream().apply { write(payload); flush() }
            }
        } catch (e: IOException) {
            throw ConsoleExtractionException("Could not reach the console's payload loader at ${ftpConfig.host}:$port (${e.message}). Make sure your loader is running on that port.")
        }
    }

    /** Runs steps 2-4. Cancellable; polls with [pollIntervalMs]. */
    suspend fun extract(
        archiveName: String, extractLocation: String, password: String?, deleteAfter: Boolean,
        payload: ByteArray, payloadPort: Int = UnrarConfig.DEFAULT_PAYLOAD_PORT,
        pollIntervalMs: Long = 2_000, timeoutMs: Long = 6L * 3600_000, onStatus: (String) -> Unit = {}
    ): ExtractionResult {
        val ftp = FtpClient(ftpConfig, log)
        try {
            val name = Names.sanitizeFileName(archiveName)
            val target = Names.normalizeRemoteDir(extractLocation).let { if (it == UnrarConfig.DIR) "/data/homebrew" else it }
            validatePayload(payload)
            // Build (and thereby validate) the config before touching the network or the console.
            val cfg = UnrarConfig.generate(name, UnrarConfig.DIR, target, password, deleteAfter).toByteArray(Charsets.UTF_8)
            return withContext(Dispatchers.IO) {
                ftp.connect(); ftp.login()
                onStatus("Writing ${UnrarConfig.CONFIG_PATH}")
                ftp.storeBytes(UnrarConfig.CONFIG_PATH, cfg)
                var offset = (ftp.size(UnrarConfig.LOG_PATH) as? SizeResult.Found)?.bytes ?: 0L

                onStatus("Sending extractor payload to ${ftpConfig.host}:$payloadPort")
                sendPayload(payloadPort, payload)
                onStatus("Console is extracting $name")

                val started = System.currentTimeMillis()
                var polls = 0
                while (true) {
                    delay(pollIntervalMs)
                    polls++
                    if (System.currentTimeMillis() - started > timeoutMs) return@withContext ExtractionResult.Failure("Timed out waiting for the console.")
                    val appended = runCatching { ftp.retrieve(UnrarConfig.LOG_PATH, offset) }.getOrDefault(ByteArray(0))
                    if (appended.isNotEmpty()) {
                        offset += appended.size
                        for (line in String(appended, Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() }) {
                            when (val ev = UnrarLog.parseLine(line)) {
                                is UnrarEvent.Done -> return@withContext finish(ftp, name, deleteAfter, ExtractionResult.Success(ev.titleId, ev.finalPath, "Extracted on the console."))
                                is UnrarEvent.Result -> return@withContext if (ev.ok)
                                    finish(ftp, name, deleteAfter, ExtractionResult.Success(null, null, "Extracted on the console."))
                                else ExtractionResult.Failure("Extraction failed (code=${ev.code}, reason=${ev.reason})")
                                UnrarEvent.Skipped -> return@withContext ExtractionResult.Success(null, null, "The console reported the content is already installed.")
                                UnrarEvent.NormalizeError -> return@withContext ExtractionResult.Success(null, null, "Extracted, but the console could not normalise the folder layout; check ${UnrarConfig.LOG_PATH}.")
                                is UnrarEvent.Failed -> return@withContext ExtractionResult.Failure(ev.line)
                                null -> {}
                            }
                        }
                    }
                    // unrar-ps5 deletes the archive when it finishes with delete_after=1
                    if (polls % 3 == 0 && deleteAfter) {
                        val names = runCatching { ftp.list(UnrarConfig.DIR).map { it.name } }.getOrNull()
                        if (names != null && name !in names) return@withContext ExtractionResult.Success(null, null, "Archive was consumed by the console extractor.")
                    }
                }
                @Suppress("UNREACHABLE_CODE") ExtractionResult.Failure("unreachable")
            }
        } catch (e: CancellationException) { throw e
        } catch (e: ConsoleExtractionException) { return ExtractionResult.Failure(e.message ?: "Extraction failed")
        } catch (e: IllegalArgumentException) { return ExtractionResult.Failure(e.message ?: "Invalid extraction settings")
        } catch (e: IOException) { return ExtractionResult.Failure("Lost contact with the console: ${e.message}")
        } finally { ftp.abortNow(); ftp.close() }
    }

    private fun finish(ftp: FtpClient, name: String, deleteAfter: Boolean, r: ExtractionResult.Success): ExtractionResult {
        if (deleteAfter) runCatching { if (ftp.fileExists("${UnrarConfig.DIR}/$name")) ftp.delete("${UnrarConfig.DIR}/$name") }
        return r
    }
}
