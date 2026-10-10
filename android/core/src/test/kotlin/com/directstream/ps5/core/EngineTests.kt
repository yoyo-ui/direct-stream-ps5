package com.directstream.ps5.core

import com.directstream.ps5.core.engine.*
import com.directstream.ps5.core.ftp.FtpConfig
import com.directstream.ps5.core.http.HttpSource
import com.directstream.ps5.core.net.UrlPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class EngineTests {
    private val size = 3_000_000L + 123
    private lateinit var ftp: MockFtpServer
    private lateinit var web: MockHttpServer
    private val dir = "/data/homebrew"
    private val finalPath = "$dir/file.bin"
    private fun part(job: String = "job1") = "$dir/file.bin.$job.ps5part"

    @Before fun up() { ftp = MockFtpServer("u", "p"); web = MockHttpServer(size); ftp.dirs += "/data"; ftp.dirs += dir }
    @After fun down() { ftp.close(); web.close() }

    private fun settings(parallel: Boolean = true, workers: Int = 4) = EngineSettings(
        workers = workers, chunkSizeBytes = 256L shl 10, bufferBytes = 2L shl 20, maxRetries = 3, retryBaseDelayMs = 10,
        parallel = parallel, ioBlockBytes = 64 * 1024, progressIntervalMs = 25)

    private fun engine(s: EngineSettings = settings(), budget: MemoryBudget = MemoryBudget(64L shl 20), allowPrivate: Boolean = true,
                       user: String = "u", pass: String = "p", port: Int = ftp.port, log: (String) -> Unit = {}) =
        TransferEngine(FtpConfig("127.0.0.1", port, user, pass, 2000, 5000, 5000),
            HttpSource(UrlPolicy(allowPrivate), 2000, 5000), budget, s, log)

    private fun remote(path: String = "/file.bin", job: String = "job1", overwrite: Boolean = false, hint: ResumeHint? = null) =
        TransferRequest(job, SourceSpec.Remote(web.base + path), dir, null, overwrite, hint)

    private fun run(e: TransferEngine, r: TransferRequest, c: TransferControl = TransferControl(), prog: (Progress) -> Unit = {}) =
        runBlocking { e.run(r, c, onProgress = prog) }

    private fun expectedBytes(n: Long = size) = Pattern.bytes(0, n.toInt())
    private fun completed(o: TransferOutcome) = (o as? TransferOutcome.Completed) ?: fail("Expected Completed but got $o")
    private fun failed(o: TransferOutcome) = (o as? TransferOutcome.Failed) ?: fail("Expected Failed but got $o")

    // ---------------------------------------------------------------- happy paths

    @Test fun singleStreamUploadsAndFinalisesAtomically() {
        val o = completed(run(engine(settings(parallel = false)), remote()))
        assertEquals(TransferMode.SINGLE_STREAM, o.mode); assertEquals(Verification.SIZE_MATCH, o.verification)
        assertContentEquals(expectedBytes(), ftp.get(finalPath)); assertTrue(ftp.parts().isEmpty())
        // the data only ever existed under a temporary name until the rename
        assertTrue(ftp.commands.indexOfFirst { it.startsWith("RNTO") } > ftp.commands.indexOfFirst { it.startsWith("STOR") })
        assertTrue(ftp.commands.any { it.startsWith("STOR /data/homebrew/file.bin.job1.ps5part") })
    }

    @Test fun parallelRangesAreDeliveredInOrderThroughOneWriter() {
        val o = completed(run(engine(), remote()))
        assertEquals(TransferMode.PARALLEL_ORDERED, o.mode)
        assertContentEquals(expectedBytes(), ftp.get(finalPath))
        assertEquals(1, ftp.storCount.get(), "one FTP writer, never concurrent STORs")
        assertTrue(web.rangeRequests.get() > 3)
    }

    @Test fun serverWithoutRangeSupportFallsBackToOneStream() {
        web.honorRange = false
        val o = completed(run(engine(), remote()))
        assertEquals(TransferMode.SINGLE_STREAM, o.mode); assertContentEquals(expectedBytes(), ftp.get(finalPath))
    }

    @Test fun localFileStreamsWithUnknownLengthAndIsVerifiedHonestly() {
        val src = SourceSpec.Local("local.bin", null, "id", false) { Pattern.stream(1_000_000, null, it) }
        val o = completed(run(engine(), TransferRequest("j2", src, dir, "local.bin")))
        assertEquals(Verification.REMOTE_SIZE_MATCHES_SENT, o.verification); assertEquals(1_000_000, ftp.get("$dir/local.bin")!!.size)
    }

    @Test fun localFileWithKnownLength() {
        val src = SourceSpec.Local("local.bin", 700_000, "id", true) { Pattern.stream(700_000, null, it) }
        assertEquals(Verification.SIZE_MATCH, completed(run(engine(), TransferRequest("j3", src, dir, "local.bin"))).verification)
    }

    @Test fun serverSideChecksumIsUsedWhenOffered() {
        ftp.supportsXmd5 = true
        assertEquals(Verification.CHECKSUM_MATCH, completed(run(engine(settings(parallel = false)), remote())).verification)
    }

    @Test fun corruptedUploadIsCaughtByChecksumAndDiscarded() {
        ftp.supportsXmd5 = true; ftp.corruptStored = true
        val f = failed(run(engine(settings(parallel = false)), remote()))
        assertEquals(ErrorKind.CHECKSUM_MISMATCH, f.error.kind); assertTrue(ftp.parts().isEmpty()); assertNull(ftp.get(finalPath))
    }

    @Test fun landingPageLinkIsResolvedAndTransferred() {
        completed(run(engine(), remote("/page-link"))); assertContentEquals(expectedBytes(), ftp.get(finalPath))
    }

    // ---------------------------------------------------------------- safety

    @Test fun existingFilesAreProtectedUnlessOverwriteIsChosen() {
        ftp.put(finalPath, byteArrayOf(9, 9, 9))
        val f = failed(run(engine(), remote()))
        assertEquals(ErrorKind.DESTINATION_EXISTS, f.error.kind); assertContentEquals(byteArrayOf(9, 9, 9), ftp.get(finalPath)); assertEquals(0, ftp.storCount.get())
        completed(run(engine(), remote(overwrite = true))); assertContentEquals(expectedBytes(), ftp.get(finalPath))
    }

    @Test fun refusedRenameLeavesAVerifiedPartialAndReportsIt() {
        ftp.rejectRename = true
        val f = failed(run(engine(), remote()))
        assertEquals(ErrorKind.RENAME_FAILED, f.error.kind); assertTrue(f.error.recoverable)
        assertContentEquals(expectedBytes(), ftp.get(part())); assertNull(ftp.get(finalPath))
    }

    @Test fun anIncompleteUploadIsNeverReportedAsSuccess() {
        ftp.keepOnlyBytes = 1_000_000
        val f = failed(run(engine(settings(parallel = false)), remote()))
        assertEquals(ErrorKind.INCOMPLETE_UPLOAD, f.error.kind); assertTrue(f.error.recoverable)
        assertNull(ftp.get(finalPath)); assertNotNull(ftp.get(part()))
    }

    @Test fun consoleWithoutEnoughSpaceIsRefusedUpFront() {
        ftp.avbl = 1000
        assertEquals(ErrorKind.INSUFFICIENT_SPACE, failed(run(engine(), remote())).error.kind); assertEquals(0, ftp.storCount.get())
    }

    @Test fun destinationTraversalIsRejected() {
        val r = remote().copy(destinationDir = "/data/../etc")
        assertEquals(ErrorKind.INVALID_REQUEST, failed(run(engine(), r)).error.kind)
    }

    @Test fun unsafeUrlsAreRejectedBeforeAnyConnectionToTheConsoleContent() {
        val r = TransferRequest("j", SourceSpec.Remote("file:///etc/passwd"), dir)
        assertEquals(ErrorKind.URL_REJECTED, failed(run(engine(), r)).error.kind)
        assertEquals(ErrorKind.URL_REJECTED, failed(run(engine(allowPrivate = false), remote())).error.kind)
    }

    @Test fun authAndConnectionFailuresAreDistinct() {
        assertEquals(ErrorKind.FTP_AUTH, failed(run(engine(pass = "wrong"), remote())).error.kind)
        val dead = java.net.ServerSocket(0).use { it.localPort }
        assertEquals(ErrorKind.FTP_UNREACHABLE, failed(run(engine(port = dead), remote())).error.kind)
    }

    @Test fun sourceProblemsAreReported() {
        assertEquals(ErrorKind.SOURCE_GONE, failed(run(engine(), remote("/gone"))).error.kind)
        assertEquals(ErrorKind.SOURCE_AUTH, failed(run(engine(), remote("/auth"))).error.kind)
        web.badContentRange = true
        assertTrue(failed(run(engine(), remote())).error.message.contains("Content-Range"))
    }

    @Test fun secretsNeverReachLogsOrErrors() {
        ftp.close(); ftp = MockFtpServer("u", "hunter2-pass"); ftp.dirs += "/data"; ftp.dirs += dir
        val logs = CopyOnWriteArrayList<String>()
        val r = TransferRequest("job1", SourceSpec.Remote("${web.base}/file.bin?token=SECRET123&x=1"), dir)
        val o = run(engine(pass = "hunter2-pass", log = { logs += it }), r)
        completed(o)
        assertTrue(logs.isNotEmpty()); assertTrue(logs.none { "hunter2-pass" in it || "SECRET123" in it }, logs.toString())
        val bad = failed(run(engine(pass = "hunter2-WRONG", log = { logs += it }), r.copy(jobId = "job2")))
        assertFalse("hunter2" in bad.error.message); assertTrue(logs.none { "hunter2" in it })
    }

    // ---------------------------------------------------------------- interruptions

    @Test fun aSourceThatDiesMidTransferIsResumedByRange() {
        web.failFirstBodyAfter = 1_000_000
        val o = completed(run(engine(settings(parallel = false)), remote()))
        assertContentEquals(expectedBytes(), ftp.get(finalPath)); assertTrue(ftp.storCount.get() >= 2, "second session continued the upload")
        assertTrue(ftp.commands.any { it.startsWith("REST") })
        assertTrue(o.bytes == size)
    }

    @Test fun aDroppedFtpDataConnectionIsRecoveredWithRest() {
        ftp.dropStoreAfterBytes = 1_000_000
        val o = completed(run(engine(settings(parallel = false)), remote()))
        assertContentEquals(expectedBytes(), ftp.get(finalPath)); assertTrue(ftp.storCount.get() >= 2); assertTrue(o.verification == Verification.SIZE_MATCH)
    }

    @Test fun aFileThatChangesDuringTransferStopsTheJobAndDiscardsThePartial() {
        web.changeEtagAfterRequests = 2
        val f = failed(run(engine(), remote()))
        assertEquals(ErrorKind.SOURCE_CHANGED, f.error.kind); assertFalse(f.error.recoverable)
        assertTrue(ftp.parts().isEmpty()); assertNull(ftp.get(finalPath))
    }

    @Test fun pauseKeepsThePartialAndALaterRunResumesIt() {
        ftp.readDelayMsPer64k = 6
        val ctl = TransferControl()
        val paused = run(engine(settings(parallel = false)), remote(), ctl) { if (it.bytesDone > 800_000) ctl.pause() }
        assertTrue(paused is TransferOutcome.Paused, "was $paused")
        val partial = ftp.get(part())!!
        assertTrue(partial.size in 1 until size.toInt()); assertContentEquals(Pattern.bytes(0, partial.size), partial)

        ftp.readDelayMsPer64k = 0
        val hint = ResumeHint("\"v1\"", web.lastModified, size, null)
        val o = completed(run(engine(settings(parallel = false)), remote(hint = hint)))
        assertTrue(o.resumedFrom > 0, "resumed from ${o.resumedFrom}"); assertContentEquals(expectedBytes(), ftp.get(finalPath))
    }

    @Test fun resumeIsRefusedWhenTheSourceValidatorsDoNotMatch() {
        ftp.put(part(), ByteArray(500_000) { 0x55 })                         // stale/garbage partial from another version
        val stale = ResumeHint("\"OLD-ETAG\"", null, size, null)
        val o = completed(run(engine(settings(parallel = false)), remote(hint = stale)))
        assertEquals(0, o.resumedFrom); assertContentEquals(expectedBytes(), ftp.get(finalPath))
    }

    @Test fun resumeIsRefusedWithoutAnyValidator() {
        web.etag = null; web.lastModified = null
        ftp.put(part(), Pattern.bytes(0, 500_000))
        val o = completed(run(engine(settings(parallel = false)), remote(hint = ResumeHint(null, null, size, null))))
        assertEquals(0, o.resumedFrom)
    }

    @Test fun localSourceResumeRequiresSameIdentityAndSeekability() {
        ftp.put("$dir/l.bin.job9.ps5part", Pattern.bytes(0, 400_000))
        fun src(id: String, seek: Boolean) = SourceSpec.Local("l.bin", 900_000, id, seek) { Pattern.stream(900_000, null, it) }
        val ok = completed(run(engine(), TransferRequest("job9", src("size:mtime", true), dir, "l.bin", resume = ResumeHint(null, null, 900_000, "size:mtime"))))
        assertEquals(400_000, ok.resumedFrom)
        ftp.dirs += dir; ftp.put("$dir/m.bin.job8.ps5part", ByteArray(400_000) { 1 })
        val changed = completed(run(engine(), TransferRequest("job8", SourceSpec.Local("m.bin", 900_000, "NEW", true) { Pattern.stream(900_000, null, it) }, dir, "m.bin",
            resume = ResumeHint(null, null, 900_000, "OLD"))))
        assertEquals(0, changed.resumedFrom); assertContentEquals(Pattern.bytes(0, 900_000), ftp.get("$dir/m.bin"))
    }

    @Test fun serverWithoutRestFallsBackToAppendOrRestart() {
        ftp.supportsRest = false
        ftp.put(part(), Pattern.bytes(0, 500_000))
        val o = completed(run(engine(settings(parallel = false)), remote(hint = ResumeHint("\"v1\"", web.lastModified, size, null))))
        assertContentEquals(expectedBytes(), ftp.get(finalPath))          // via APPE, or a clean restart; never corrupt
        assertTrue(o.resumedFrom == 500_000L || o.resumedFrom == 0L)
    }

    @Test fun cancelWithDeleteRemovesThePartial() {
        ftp.readDelayMsPer64k = 6
        val ctl = TransferControl()
        val o = run(engine(settings(parallel = false)), remote(), ctl) { if (it.bytesDone > 800_000) ctl.cancel(deletePartial = true) }
        assertTrue(o is TransferOutcome.Cancelled && o.partialDeleted, "was $o"); assertTrue(ftp.parts().isEmpty()); assertNull(ftp.get(finalPath))
    }

    @Test fun cancelWithoutDeleteKeepsThePartial() {
        ftp.readDelayMsPer64k = 6
        val ctl = TransferControl()
        val o = run(engine(settings(parallel = false)), remote(), ctl) { if (it.bytesDone > 800_000) ctl.cancel(deletePartial = false) }
        assertTrue(o is TransferOutcome.Cancelled); assertEquals(1, ftp.parts().size)
    }

    // ---------------------------------------------------------------- bounded memory

    @Test fun aFastSourceCannotRunAheadOfASlowConsole() {
        val total = 40L shl 20
        ftp.readDelayMsPer64k = 4
        val produced = AtomicLong()
        val src = SourceSpec.Local("big.bin", total, "id", false) { Pattern.stream(total, produced, it) }
        val s = settings(parallel = false).copy(bufferBytes = 4L shl 20)
        val budget = MemoryBudget(8L shl 20)
        var maxAhead = 0L; var maxBudget = 0L
        val sampler = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val received = ftp.files.values.maxOfOrNull { it.size().toLong() } ?: 0
                maxAhead = maxOf(maxAhead, produced.get() - received); maxBudget = maxOf(maxBudget, budget.inUseBytes)
                try { Thread.sleep(20) } catch (_: InterruptedException) { break }
            }
        }.also { it.start() }
        val o = run(engine(s, budget), TransferRequest("big", src, dir, "big.bin")); sampler.interrupt(); sampler.join()
        completed(o); assertEquals(total.toInt(), ftp.get("$dir/big.bin")!!.size)
        assertTrue(maxAhead < 14L shl 20, "source ran ${maxAhead shr 20} MiB ahead of the console (buffer 4 MiB)")
        assertTrue(maxBudget <= 8L shl 20); assertEquals(0, budget.inUseBytes, "budget fully released")
    }

    @Test fun parallelModeStaysWithinTheMemoryBudgetAndReleasesIt() {
        web.close(); web = MockHttpServer(24L shl 20)
        val budget = MemoryBudget(3L shl 20)
        var peak = 0L
        val o = run(engine(settings(workers = 8), budget), remote()) { peak = maxOf(peak, budget.inUseBytes) }
        completed(o); assertTrue(peak <= 3L shl 20); assertEquals(0, budget.inUseBytes)
        assertEquals((24L shl 20).toInt(), ftp.get(finalPath)!!.size)
    }

    @Test fun tooSmallABudgetFallsBackToSingleStream() {
        val o = completed(run(engine(settings(workers = 8), MemoryBudget(300L shl 10)), remote()))
        assertEquals(TransferMode.SINGLE_STREAM, o.mode); assertContentEquals(expectedBytes(), ftp.get(finalPath))
    }
}
