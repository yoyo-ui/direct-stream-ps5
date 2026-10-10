package com.directstream.ps5.core

import com.directstream.ps5.core.archive.*
import com.directstream.ps5.core.diag.*
import com.directstream.ps5.core.engine.*
import com.directstream.ps5.core.engine.MemoryBudget
import com.directstream.ps5.core.http.*
import com.directstream.ps5.core.net.UrlPolicy
import com.directstream.ps5.core.net.UrlRejectedException
import com.directstream.ps5.core.resolver.Resolver
import com.directstream.ps5.core.routing.DestinationRouter
import com.directstream.ps5.core.routing.RoutingRule
import com.directstream.ps5.core.util.Names
import com.directstream.ps5.core.util.Redaction
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class RedactionTests {
    @Test fun urlCredentialsAndSensitiveQueryAreRemoved() {
        val r = Redaction.redactUrl("https://user:hunter2@cdn.example.com/a.bin?token=SECRET&x=1&signature=abc#frag")
        assertFalse("hunter2" in r); assertFalse("SECRET" in r); assertFalse("abc" in r); assertFalse("frag" in r)
        assertTrue("x=1" in r)
    }
    @Test fun ftpPasswordCommandIsMasked() {
        assertEquals("PASS ***", Redaction.redactFtpCommand("PASS hunter2"))
        assertEquals("USER bob", Redaction.redactFtpCommand("USER bob"))
    }
    @Test fun headersAndFreeTextAreRedacted() {
        assertEquals("***", Redaction.redactHeader("Authorization", "Bearer abc"))
        assertEquals("keep", Redaction.redactHeader("Accept", "keep"))
        assertFalse("abc123" in Redaction.redactText("failed fetching https://h.example/f?token=abc123 today"))
    }
}

class NamesTests {
    @Test fun fileNamesCannotEscapeOrInjectCommands() {
        assertEquals("passwd", Names.sanitizeFileName("../../etc/passwd"))
        assertEquals("evil.bin", Names.sanitizeFileName("C:\\x\\evil.bin"))
        assertEquals("download.bin", Names.sanitizeFileName(".."))
        assertEquals("a__b.pkg", Names.sanitizeFileName("a\r\nb.pkg"))
        assertTrue(Names.sanitizeFileName("x".repeat(500) + ".pkg").endsWith(".pkg"))
        assertTrue(Names.sanitizeFileName("é".repeat(300) + ".iso").toByteArray().size <= 200)
    }
    @Test fun remotePathsRejectTraversalAndControlCharacters() {
        assertEquals("/data/homebrew", Names.normalizeRemoteDir("//data/./homebrew/"))
        assertFailsWith<IllegalArgumentException> { Names.normalizeRemoteDir("/data/../etc") }
        assertFailsWith<IllegalArgumentException> { Names.normalizeRemoteDir("relative/path") }
        assertFailsWith<IllegalArgumentException> { Names.normalizeRemoteDir("/data/x\r\nDELE /") }
        assertFailsWith<IllegalArgumentException> { Names.requireSafeFtpArgument("a\u0000b") }
    }
    @Test fun archiveEntryPathsRejectZipSlip() {
        assertFailsWith<IllegalArgumentException> { Names.sanitizeRelativePath("../../evil") }
        assertEquals("dir/file.txt", Names.sanitizeRelativePath("dir\\file.txt"))
    }
}

class RoutingTests {
    @Test fun defaultsMatchTheOriginalProject() {
        val r = DestinationRouter()
        assertEquals("/data/pkg", r.directoryFor("Game.PKG"))
        for (e in listOf("ffpfsc", "exfat", "ufs", "iso")) assertEquals("/data/ShadowMount", r.directoryFor("disc.$e"))
        assertEquals("/data/homebrew", r.directoryFor("tool.elf"))
        assertEquals("/data/pkg/a.pkg", r.fullPathFor("a.pkg"))
    }
    @Test fun rulesAreEditable() {
        val r = DestinationRouter(listOf(RoutingRule(listOf("pkg"), "/mnt/ext0/pkg")), "/data/other")
        assertEquals("/mnt/ext0/pkg", r.directoryFor("a.pkg")); assertEquals("/data/other", r.directoryFor("a.zip"))
    }
    @Test fun badRuleDirectoryIsRejectedNotUsed() {
        assertFailsWith<IllegalArgumentException> { DestinationRouter(listOf(RoutingRule(listOf("pkg"), "/data/../x"))).directoryFor("a.pkg") }
    }
}

class RangeTests {
    @Test fun validContentRange() {
        assertEquals(ContentRange(0, 99, 1000), RangeParser.parseContentRange("bytes 0-99/1000"))
        assertEquals(null, RangeParser.parseContentRange("bytes 0-99/*")?.total)
        assertEquals(1000L, RangeParser.parseUnsatisfiedTotal("bytes */1000"))
    }
    @Test fun malformedOrContradictoryContentRangeIsRejected() {
        for (bad in listOf(null, "", "bytes banana", "bytes 5-2/10", "bytes 0-10/10", "items 0-1/2", "bytes 0-1/x"))
            assertNull(RangeParser.parseContentRange(bad), "should reject '$bad'")
    }
    @Test fun chunksTileTheRangeExactly() {
        val rnd = java.util.Random(7)
        repeat(300) {
            val total = rnd.nextInt(5_000_000).toLong() + 1
            val size = rnd.nextInt(100_000).toLong() + 1
            val start = if (rnd.nextBoolean()) 0 else rnd.nextInt(total.toInt()).toLong()
            val plan = ChunkPlanner.plan(total, size, start)
            assertTrue(ChunkPlanner.coversExactly(plan, total, start), "total=$total size=$size start=$start")
            assertTrue(plan.dropLast(1).all { it.length == size })
        }
        assertTrue(ChunkPlanner.plan(10, 3, 10).isEmpty())
    }
}

class UrlPolicyTests {
    private val publicHost = InetAddress.getByName("8.8.8.8")
    private val policy = UrlPolicy(false) { host -> when {
        host == "internal.test" -> listOf(InetAddress.getByName("192.168.1.9"))
        host.first().isDigit() -> InetAddress.getAllByName(host).toList()      // IP literals resolve to themselves
        else -> listOf(publicHost) } }
    @Test fun onlyHttpAndHttpsWithoutEmbeddedCredentials() {
        for (u in listOf("ftp://x.test/a", "file:///etc/passwd", "javascript:alert(1)")) assertFails { policy.validateInitial(URL(u)) }
        assertFailsWith<UrlRejectedException> { policy.validateInitial(URL("https://user:pw@x.test/a")) }
        policy.validateInitial(URL("https://x.test/a"))
    }
    @Test fun privateTargetsAreRefusedUnlessOptedIn() {
        assertFailsWith<UrlRejectedException> { policy.validateInitial(URL("http://internal.test/a")) }
        assertFailsWith<UrlRejectedException> { policy.validateInitial(URL("http://127.0.0.1/a")) }
        UrlPolicy(true) { listOf(InetAddress.getByName("192.168.1.9")) }.validateInitial(URL("http://internal.test/a"))
    }
    @Test fun redirectsCannotReachPrivateHostsOrDowngrade() {
        val optIn = UrlPolicy(true) { host -> if (host == "internal.test") listOf(InetAddress.getByName("10.0.0.5")) else listOf(publicHost) }
        assertFailsWith<UrlRejectedException> { optIn.validateRedirect(URL("https://x.test/a"), URL("http://y.test/b")) }
        assertFailsWith<UrlRejectedException> { optIn.validateRedirect(URL("https://x.test/a"), URL("https://internal.test/b")) }
        optIn.validateRedirect(URL("https://x.test/a"), URL("https://y.test/b"))
    }
    @Test fun privateAddressClassification() {
        for (ip in listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.1", "169.254.1.1", "100.64.0.1", "::1", "fc00::1"))
            assertTrue(UrlPolicy.isPrivateAddress(InetAddress.getByName(ip)), ip)
        assertFalse(UrlPolicy.isPrivateAddress(InetAddress.getByName("8.8.8.8")))
    }
}

/** Fixtures adapted from the original project's tests/test_resolver.py (neutral file names). */
class ResolverParityTests {
    @Test fun preResolve() {
        assertEquals("https://pixeldrain.com/api/file/abc123XYZ", Resolver.preResolveUrl("https://pixeldrain.com/u/abc123XYZ"))
        assertEquals("https://pixeldrain.com/api/file/abc123XYZ", Resolver.preResolveUrl("https://www.pixeldrain.com/u/abc123XYZ"))
        assertEquals("https://drive.google.com/uc?export=download&id=1A2B3C4D5E", Resolver.preResolveUrl("https://drive.google.com/file/d/1A2B3C4D5E/view?usp=sharing"))
        assertEquals("https://drive.google.com/uc?export=download&id=1A2B3C4D5E", Resolver.preResolveUrl("https://drive.google.com/open?id=1A2B3C4D5E"))
        assertEquals("https://archive.org/download/sample_repo/update_v105.pkg", Resolver.preResolveUrl("https://archive.org/details/sample_repo/update_v105.pkg"))
        assertEquals("https://buzzheavier.com/abc12345/download", Resolver.preResolveUrl("https://buzzheavier.com/abc12345"))
        assertEquals("https://bzzhr.to/xyz7890/download", Resolver.preResolveUrl("https://bzzhr.to/f/xyz7890"))
        assertEquals("https://example.com/plain.bin", Resolver.preResolveUrl("https://example.com/plain.bin"))
    }
    @Test fun archiveOrgDetailsPage() {
        val html = """<a href="/download/col/readme.txt">Readme</a><a href="/download/col/package_01.pkg">Download PKG</a>"""
        assertEquals("https://archive.org/download/col/package_01.pkg", Resolver.extractDownloadLinkFromHtml("https://archive.org/details/col", html))
    }
    @Test fun hostPages() {
        assertEquals("https://w.buzzheavier.com/s/direct/sample.pkg", Resolver.extractDownloadLinkFromHtml("https://buzzheavier.com/abc12345",
            """<a class="download-button" href="https://w.buzzheavier.com/s/direct/sample.pkg">Download file</a>"""))
        assertEquals("https://a-01.1fichier.com/c123456789", Resolver.extractDownloadLinkFromHtml("https://1fichier.com/?abc123",
            """<div id="dl_link"><a class="ok btn-general btn-orange" href="https://a-01.1fichier.com/c123456789">Click here</a></div>"""))
        assertEquals("https://s1.send.cm/files/sample.pkg", Resolver.extractDownloadLinkFromHtml("https://send.cm/file/xyz",
            """<a href="https://s1.send.cm/files/sample.pkg">Download</a>"""))
        assertEquals("https://download1584.mediafire.com/xyz123/update.pkg", Resolver.extractDownloadLinkFromHtml("https://www.mediafire.com/file/xyz123/update.pkg/file",
            """<a class="input popsok" aria-label="Download file" id="downloadButton" href="https://download1584.mediafire.com/xyz123/update.pkg">Download (4.2 GB)</a>"""))
        assertEquals("https://drive.usercontent.google.com/download?id=1A2B&export=download&confirm=t&uuid=xyz", Resolver.extractDownloadLinkFromHtml(
            "https://drive.google.com/uc?export=download&id=1A2B",
            """<a id="uc-download-link" class="x" href="https://drive.usercontent.google.com/download?id=1A2B&amp;export=download&amp;confirm=t&amp;uuid=xyz">Download anyway</a>"""))
        assertEquals("https://store1.example.net/f/file.bin", Resolver.extractDownloadLinkFromHtml("https://gofile.io/d/abc", """var d = {"link": "https://store1.example.net\/f\/file.bin"};"""))
    }
    @Test fun genericHeuristics() {
        assertEquals("https://releases.example.com/builds/pkg_102.pkg", Resolver.extractDownloadLinkFromHtml("https://releases.example.com/release.html",
            """<a href="https://releases.example.com/builds/pkg_102.pkg">Download</a>"""))
        // prefers primary disc/package formats over archives and text
        assertEquals("https://releases.example.com/image.ffpfsc", Resolver.extractDownloadLinkFromHtml("https://releases.example.com/p",
            """<a href="https://releases.example.com/readme.txt">r</a><a href="https://releases.example.com/assets.zip">z</a><a href="https://releases.example.com/image.ffpfsc">d</a>"""))
        assertEquals("https://example.com/next/file.zip", Resolver.extractDownloadLinkFromHtml("https://example.com/p",
            """<meta http-equiv="refresh" content="0;url=/next/file.zip">"""))
        assertNull(Resolver.extractDownloadLinkFromHtml("https://example.com/about", "<html><body><h1>Home</h1><p>No downloads here</p></body></html>"))
    }
    @Test fun filenames() {
        assertEquals("Patch_60fps.pkg", Resolver.parseFilenameFromHeaders(mapOf("Content-Disposition" to """attachment; filename="Patch_60fps.pkg"""")))
        assertEquals("My Game v1.12.pkg", Resolver.parseFilenameFromHeaders(mapOf("Content-Disposition" to "attachment; filename*=UTF-8''My%20Game%20v1.12.pkg")))
        assertEquals("[tag]-PPSA0001.rar", Resolver.parseFilenameFromHeaders(emptyMap(), "https://cdn.example.com/dld/3c86?token=xyz&filename=%5Btag%5D-PPSA0001.rar"))
        assertEquals("stellar.pkg", Resolver.parseFilenameFromHeaders(emptyMap(), "https://cdn.example.com/download/hash123?name=stellar.pkg"))
    }
    @Test fun captchaHintsHeadersAndSingleConnection() {
        assertTrue(Resolver.captchaHint("https://1fichier.com/?abc")!!.contains("1fichier"))
        assertTrue(Resolver.captchaHint("https://buzzheavier.com/abc")!!.contains("BuzzHeavier"))
        assertNull(Resolver.captchaHint("https://example.com/f"))
        assertEquals("https://www.mediafire.com/", Resolver.requestHeaders("https://download1.mediafire.com/x/y.pkg")["Referer"])
        assertEquals("identity", Resolver.requestHeaders("https://example.com/a")["Accept-Encoding"])
        assertTrue(Resolver.isSingleConnectionHost("https://a-01.1fichier.com/c1"))
        assertFalse(Resolver.isSingleConnectionHost("https://archive.org/download/i/f.pkg"))
    }
    @Test fun multipartParsingAndSorting() {
        fun p(n: String) = Resolver.parseMultipartInfo(n)?.let { it.base to it.number }
        assertEquals("Game.pkg" to 1, p("Game.pkg.001")); assertEquals("Game.pkg" to 12, p("Game.pkg.012"))
        assertEquals("Disc.pkg" to 1, p("Disc.part01.pkg")); assertEquals("Disc.pkg" to 2, p("Disc_part2.pkg"))
        assertEquals("Thing.pkg" to 3, p("Thing.pkg.part3")); assertEquals("Pack.rar" to 2, p("Pack.part02.rar"))
        assertEquals("Image.ffpfsc" to 1, p("Image.ffpfsc.001")); assertNull(p("plain.pkg"))
        val seq = Resolver.detectMultipartSequence(listOf(
            Resolver.PartItem("https://cdn.example.com/g/Game.pkg.003"), Resolver.PartItem("https://cdn.example.com/g/Game.pkg.001"), Resolver.PartItem("https://cdn.example.com/g/Game.pkg.002")))
        assertTrue(seq.isMultipart); assertEquals("Game.pkg", seq.mergedName)
        assertEquals(listOf("001", "002", "003"), seq.items.map { it.source.takeLast(3) })
        assertFalse(Resolver.detectMultipartSequence(listOf(Resolver.PartItem("x", "A.pkg.001"), Resolver.PartItem("y", "B.pkg.002"))).isMultipart)
    }
    @Test fun onlyExplicitPasswordsAreHonoured() {
        assertEquals("abc123", Resolver.explicitPasswordHint("Game password=abc123.rar"))
        assertNull(Resolver.explicitPasswordHint("[some-site.com]-Game.rar"))   // never guessed from a site name
    }
}

class ArchiveTests {
    @Test fun detectionByNameAndMagic() {
        assertEquals(ArchiveType.ZIP, ArchiveDetector.fromName("a.ZIP")!!.type)
        assertEquals(ArchiveType.SEVEN_Z, ArchiveDetector.fromName("a.7z")!!.type)
        assertNull(ArchiveDetector.fromName("a.pkg"))
        assertEquals(ArchiveType.ZIP, ArchiveDetector.fromMagic(byteArrayOf(0x50, 0x4b, 3, 4, 0, 0, 0)))
        assertEquals(ArchiveType.RAR, ArchiveDetector.fromMagic(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0)))
        assertNull(ArchiveDetector.fromMagic(ByteArray(8)))
    }
    @Test fun splitSequencesAreValidated() {
        assertTrue(ArchiveDetector.checkSequence(listOf("g.part2.rar", "g.part1.rar", "g.part3.rar")).complete)
        val gap = ArchiveDetector.checkSequence(listOf("g.part1.rar", "g.part3.rar"))
        assertEquals(listOf(2), gap.missingIndexes); assertFalse(gap.complete)
        assertFalse(ArchiveDetector.checkSequence(listOf("a.part1.rar", "b.part2.rar")).complete)
    }
    @Test fun onlySendingTheArchiveIsAvailableByDefault() {
        val o = ArchiveStrategies.options()
        assertTrue(o.first { it.strategy == ArchiveStrategy.SEND_ARCHIVE }.available)
        assertFalse(o.first { it.strategy == ArchiveStrategy.EXTRACT_ON_DEVICE }.available)
        assertFalse(o.first { it.strategy == ArchiveStrategy.EXTRACT_ON_CONSOLE }.available)
        assertTrue(ArchiveStrategies.options(consoleServiceConfigured = true).first { it.strategy == ArchiveStrategy.EXTRACT_ON_CONSOLE }.available)
    }
}

class SettingsTests {
    @Test fun hostValidation() {
        assertTrue(SettingsValidator.validateHost("").any { it.severity == Severity.ERROR })
        assertTrue(SettingsValidator.validateHost("999.1.1.1").any { it.severity == Severity.ERROR })
        assertTrue(SettingsValidator.validateHost("8.8.8.8").any { it.severity == Severity.ERROR })     // public address
        assertTrue(SettingsValidator.validateHost("192.168.1.50").isEmpty())
        assertTrue(SettingsValidator.validateHost("ps5.local").all { it.severity == Severity.WARNING })
        assertTrue(SettingsValidator.validateHost("bad host!").any { it.severity == Severity.ERROR })
    }
    @Test fun numericLimitsAndDestination() {
        val ok = EngineSettings()
        assertTrue(SettingsValidator.validate("192.168.1.2", 2121, 15, 3, ok, "/data/homebrew").none { it.severity == Severity.ERROR })
        assertTrue(SettingsValidator.validate("192.168.1.2", 70000, 15, 3, ok, "/data/homebrew").any { it.field == "port" })
        assertTrue(SettingsValidator.validate("192.168.1.2", 2121, 0, 3, ok, "/data/homebrew").any { it.field == "timeout" })
        assertTrue(SettingsValidator.validate("192.168.1.2", 2121, 15, 3, ok, "/data/../x").any { it.field == "destination" })
        assertTrue(SettingsValidator.validate("192.168.1.2", 2121, 15, 3, ok.copy(workers = 99), "/d").any { it.field == "workers" })
    }
    @Test fun presetsAndDeviceClamp() {
        assertEquals(2, Presets.CONSERVATIVE.workers); assertEquals(4, Presets.BALANCED.workers); assertEquals(8, Presets.TURBO.workers)
        val (s, why) = Presets.clampToDevice(Presets.TURBO.copy(bufferBytes = 1024L shl 20), 64L shl 20)
        assertTrue(s.bufferBytes <= 32L shl 20); assertNotNull(why)
        assertTrue(s.bufferBytes / s.chunkSizeBytes >= 2 || s.workers == 1)
    }
    @Test fun recommendationsUseOnlyMeasuredNumbers() {
        assertTrue(Diagnostics.recommend(null, null, null).isEmpty())
        val r = Diagnostics.recommend(BenchResult(10_000_000, 1000), BenchResult(10_000_000, 4000), false)
        assertTrue(r.any { "10.0 MB/s" in it }); assertTrue(r.any { "bottleneck" in it }); assertTrue(r.any { "range" in it.lowercase() })
    }
}

class RecoveryTests {
    private fun job(state: JobState = JobState.TRANSFERRING, remote: Boolean = true, ranges: Boolean = true, validator: Boolean = true,
                    seekable: Boolean = true, perm: Boolean = true, partial: Boolean = true) =
        PersistedJob(state, remote, ranges, validator, seekable, perm, partial)
    @Test fun idleJobsAreLeftAlone() {
        for (s in listOf(JobState.QUEUED, JobState.PAUSED, JobState.COMPLETED, JobState.FAILED, JobState.CANCELLED))
            assertEquals(RecoveryAction.UNCHANGED, JobRecovery.onProcessRestart(job(s)))
    }
    @Test fun activeJobsBecomePausedAndResumableWhenSafe() {
        assertEquals(RecoveryAction.PAUSE_RESUMABLE, JobRecovery.onProcessRestart(job()))
        assertEquals(RecoveryAction.PAUSE_RESUMABLE, JobRecovery.onProcessRestart(job(remote = false, seekable = true, ranges = false, validator = false)))
    }
    @Test fun unsafeResumesMustRestart() {
        for (j in listOf(job(ranges = false), job(validator = false), job(partial = false), job(remote = false, seekable = false)))
            { assertEquals(RecoveryAction.PAUSE_RESTART_REQUIRED, JobRecovery.onProcessRestart(j)); assertNotNull(JobRecovery.restartReason(j)) }
    }
    @Test fun lostLocalPermissionFailsTheJob() {
        assertEquals(RecoveryAction.FAIL_SOURCE_LOST, JobRecovery.onProcessRestart(job(remote = false, perm = false)))
    }
}

class MemoryBudgetTests {
    @Test fun neverHandsOutMoreThanTheTotal() = runBlocking {
        val b = MemoryBudget(10)
        val l1 = b.acquire(6); val l2 = b.acquire(4)
        assertEquals(10, b.inUseBytes)
        val waiting = async { b.acquire(5) }
        delay(100); assertFalse(waiting.isCompleted)
        l1.release(); delay(100)
        assertTrue(waiting.isCompleted); assertTrue(b.inUseBytes <= 10)
        l1.release()                       // idempotent
        assertEquals(9, b.inUseBytes); l2.release(); waiting.await().release(); assertEquals(0, b.inUseBytes)
    }
    @Test fun cancelledWaitersReturnTheirShare() = runBlocking {
        val b = MemoryBudget(10); val hold = b.acquire(10)
        val w = launch { b.acquire(5) }; delay(50); w.cancelAndJoin()
        hold.release(); assertEquals(0, b.inUseBytes)
        b.acquire(10).release()
    }
    @Test fun requestsAreServedInOrderAndOversizedOnesAreClamped() = runBlocking {
        val b = MemoryBudget(10); val hold = b.acquire(10); val order = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val a = launch { b.acquire(8).also { order += 1 }.release() }; delay(30)
        val c = launch { b.acquire(2).also { order += 2 }.release() }; delay(30)
        hold.release(); a.join(); c.join()
        assertEquals(listOf(1, 2), order)
        assertEquals(10, b.acquire(1000).bytes)
    }
    @Test fun manyConcurrentUsersStayWithinTheBudget() = runBlocking {
        val b = MemoryBudget(100); val peak = AtomicInteger(); val cur = AtomicInteger()
        coroutineScope { repeat(40) { launch(Dispatchers.Default) { val l = b.acquire(30); val v = cur.addAndGet(30); peak.accumulateAndGet(v, ::maxOf); delay(5); cur.addAndGet(-30); l.release() } } }
        assertTrue(peak.get() <= 100); assertEquals(0, b.inUseBytes)
    }
}
