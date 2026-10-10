package com.directstream.ps5.core

import com.directstream.ps5.core.console.*
import com.directstream.ps5.core.ftp.FtpConfig
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class ConsoleExtractorTests {
    private lateinit var ftp: MockFtpServer
    private lateinit var loader: ServerSocket
    private val received = AtomicReference<ByteArray>()
    private val elf = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + ByteArray(200) { it.toByte() }
    /** What the "console" appends to unrar.log after it receives the payload. */
    @Volatile private var logAfterPayload = "extract_start archive=a.rar\ndone archive=a.rar title_id=PPSA00001 final_path=/data/homebrew/Sample\n"

    @Before fun up() {
        ftp = MockFtpServer(); ftp.dirs += "/data"; ftp.dirs += "/data/unrar"; ftp.dirs += "/data/homebrew"
        loader = ServerSocket(0)
        Thread { try { loader.accept().use { s -> received.set(s.getInputStream().readBytes())
            ftp.files["/data/unrar/unrar.log"] = MockFtpServer.FileData().also { it.buf.write(logAfterPayload.toByteArray()) } } } catch (_: Exception) {} }.apply { isDaemon = true }.start()
    }
    @After fun down() { ftp.close(); runCatching { loader.close() } }
    private fun extractor() = ConsoleExtractor(FtpConfig("127.0.0.1", ftp.port, "u", "p", 2000, 5000, 5000))
    private fun go(name: String = "a.rar", pw: String? = "pw", del: Boolean = true, payload: ByteArray = elf, port: Int = loader.localPort) =
        runBlocking { extractor().extract(name, "/data/homebrew", pw, del, payload, port, pollIntervalMs = 30, timeoutMs = 5000) }

    @Test fun configIniMatchesTheOriginalImplementationByteForByte() {
        // Expected strings were produced by running generate_unrar_config() from the v2.9.0 Python source.
        assertEquals("filename=a.rar\narchive_location=/data/unrar\narchive_password=pw\ndelete_after=1\nextract_location=/data/homebrew\nthreads=0\nnice=-20\ncpu_mask=0\nprogress=10\n",
            UnrarConfig.generate("a.rar", "/data/unrar", "/data/homebrew", "pw", true))
        assertEquals("filename=b.rar\narchive_location=/data/unrar\narchive_password=\ndelete_after=0\nextract_location=/data/x\nthreads=0\nnice=-20\ncpu_mask=0\nprogress=10\n",
            UnrarConfig.generate("b.rar", extractLocation = "/data/x", password = null, deleteAfter = false))
    }

    @Test fun logLinesAreInterpretedLikeTheOriginal() {
        assertEquals(UnrarEvent.Done("PPSA1", "/data/x"), UnrarLog.parseLine("done archive=a title_id=PPSA1 final_path=/data/x"))
        assertTrue((UnrarLog.parseLine("extract_result code=0 reason=success") as UnrarEvent.Result).ok)
        assertFalse((UnrarLog.parseLine("extract_result code=11 reason=bad_password") as UnrarEvent.Result).ok)
        assertEquals(UnrarEvent.Skipped, UnrarLog.parseLine("skip archive=a already_installed"))
        assertEquals(UnrarEvent.NormalizeError, UnrarLog.parseLine("normalize_error x"))
        assertTrue(UnrarLog.parseLine("UnRAR error: bad data") is UnrarEvent.Failed)
        assertNull(UnrarLog.parseLine("progress 10%"))
    }

    @Test fun fullFlowWritesConfigSendsPayloadAndReadsTheResult() {
        val r = go()
        assertTrue(r is ExtractionResult.Success, "was $r"); r as ExtractionResult.Success
        assertEquals("PPSA00001", r.titleId); assertEquals("/data/homebrew/Sample", r.finalPath)
        assertContentEquals(elf, received.get())                                       // the payload arrived intact, raw
        assertEquals(UnrarConfig.generate("a.rar", "/data/unrar", "/data/homebrew", "pw", true), String(ftp.get("/data/unrar/config.ini")!!))
        // order: config written before the payload is sent (the log only appears once the payload arrived)
        assertTrue(ftp.commands.indexOfFirst { it.startsWith("STOR /data/unrar/config.ini") } >= 0)
    }

    @Test fun failureReportedByTheConsoleIsSurfaced() {
        logAfterPayload = "extract_result code=11 reason=bad_password\n"
        val r = go(); assertTrue(r is ExtractionResult.Failure && "bad_password" in r.reason, "was $r")
    }

    @Test fun hardErrorLinesFail() {
        logAfterPayload = "UnRAR error: corrupt header\n"
        assertTrue(go() is ExtractionResult.Failure)
    }

    @Test fun nonElfFilesAreNeverSent() {
        val r = go(payload = "#!/bin/sh\nrm -rf /".toByteArray()); assertTrue(r is ExtractionResult.Failure)
        assertNull(received.get())
    }

    @Test fun anUnreachableLoaderGivesAClearMessage() {
        val dead = ServerSocket(0).use { it.localPort }
        val r = go(port = dead); assertTrue(r is ExtractionResult.Failure && "payload loader" in r.reason, "was $r")
    }

    @Test fun passwordsCannotInjectConfigLines() {
        assertFailsWith<IllegalArgumentException> { UnrarConfig.generate("a.rar", password = "x\ndelete_after=0") }
        val r = go(pw = "x\nextract_location=/data"); assertTrue(r is ExtractionResult.Failure || r is ExtractionResult.Success)
        assertFalse(ftp.get("/data/unrar/config.ini")?.let { String(it).contains("extract_location=/data\n") } ?: false)
    }

    @Test fun waitingCanBeCancelled() = runBlocking {
        logAfterPayload = "working...\n"
        val job = launch { extractor().extract("a.rar", "/data/homebrew", null, false, elf, loader.localPort, pollIntervalMs = 30, timeoutMs = 60_000) }
        delay(600); job.cancelAndJoin(); assertTrue(job.isCancelled)
    }
}
