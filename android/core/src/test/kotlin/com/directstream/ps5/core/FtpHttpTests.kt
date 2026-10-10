package com.directstream.ps5.core

import com.directstream.ps5.core.ftp.*
import com.directstream.ps5.core.http.*
import com.directstream.ps5.core.net.UrlPolicy
import com.directstream.ps5.core.net.UrlRejectedException
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.*

class FtpParserTests {
    @Test fun multiLineAndMalformedReplies() {
        val lines = ArrayDeque(listOf("211-Features:", " SIZE", " REST STREAM", "211 End"))
        val r = FtpResponseParser.read { lines.removeFirstOrNull() }
        assertEquals(211, r.code); assertEquals(setOf("SIZE", "REST STREAM"), FtpResponseParser.parseFeatures(r))
        assertFailsWith<FtpProtocolException> { FtpResponseParser.read { "garbage" } }
        assertFailsWith<FtpConnectionException> { FtpResponseParser.read { null } }
        val unterminated = ArrayDeque(listOf("226-working", "still"))
        assertFailsWith<FtpConnectionException> { FtpResponseParser.read { unterminated.removeFirstOrNull() } }
    }
    @Test fun passiveReplies() {
        assertEquals(2121, FtpResponseParser.parsePasvPort("Entering Passive Mode (10,0,0,5,8,73)"))
        assertEquals(50000, FtpResponseParser.parseEpsvPort("Entering Extended Passive Mode (|||50000|)"))
        assertFailsWith<FtpProtocolException> { FtpResponseParser.parsePasvPort("nonsense") }
        assertFailsWith<FtpProtocolException> { FtpResponseParser.parsePasvPort("(1,2,3,4,0,0)") }
    }
    @Test fun listFormats() {
        val unix = FtpListParser.parseList(listOf("total 4", "drwxr-xr-x 2 ftp ftp 4096 Jan  2 03:04 My Folder", "-rw-r--r-- 1 ftp ftp 123 Jan  2 2023 a file.pkg", "lrwxrwxrwx 1 a b 5 Jan 1 00:00 link -> target", "drwx------ 2 u g 0 Jan 1 00:00 ."))
        assertEquals(listOf("My Folder", "a file.pkg", "link"), unix.map { it.name })
        assertTrue(unix[0].isDirectory); assertEquals(123L, unix[1].size)
        val dos = FtpListParser.parseList(listOf("01-02-24  03:04PM       <DIR>          games", "01-02-24  03:04PM              5000 x.pkg"))
        assertTrue(dos[0].isDirectory); assertEquals(5000L, dos[1].size)
        val m = FtpListParser.parseMlsd(listOf("type=cdir;modify=20240102030405; .", "type=dir;modify=20240102030405; sub dir", "type=file;size=77;modify=20240102030405; a b.bin"))
        assertEquals(listOf("sub dir", "a b.bin"), m.map { it.name }); assertEquals(77L, m[1].size); assertEquals("2024-01-02 03:04:05", m[1].modified)
    }
}

class FtpClientTests {
    private lateinit var srv: MockFtpServer
    @Before fun up() { srv = MockFtpServer() }
    @After fun down() { srv.close() }
    private fun cfg(user: String = "u", pass: String = "p", port: Int = srv.port) = FtpConfig("127.0.0.1", port, user, pass, 2000, 5000, 5000)
    private fun client(c: FtpConfig = cfg()) = FtpClient(c).also { it.connect(); it.login() }

    @Test fun loginSucceedsAndDiscoversFeatures() {
        val c = client(); assertTrue(c.supports("SIZE")); assertTrue(c.supports("REST")); assertFalse(c.supports("XMD5")); c.quit()
    }
    @Test fun badPasswordIsAnAuthFailureAndNeverLogged() {
        val lines = mutableListOf<String>()
        val c = FtpClient(cfg(pass = "wrong-pass-123")) { lines += it }
        c.connect()
        assertFailsWith<FtpAuthException> { c.login() }
        assertTrue(lines.none { "wrong-pass-123" in it }); assertTrue(lines.any { it.contains("PASS ***") })
        assertTrue(srv.commands.none { "wrong-pass-123" in it })
    }
    @Test fun unreachableServerIsAConnectionError() {
        val dead = java.net.ServerSocket(0).use { it.localPort }
        assertFailsWith<FtpConnectionException> { FtpClient(cfg(port = dead)).connect() }
    }
    @Test fun directoriesAreCreatedLevelByLevel() {
        val c = client(); c.makeDirectories("/data/homebrew/sub"); assertTrue("/data/homebrew/sub" in srv.dirs); assertTrue(c.directoryExists("/data/homebrew"))
        assertFalse(c.directoryExists("/nope"))
    }
    @Test fun sizeDistinguishesFoundMissingAndUnsupported() {
        srv.put("/a.bin", ByteArray(10)); val c = client()
        assertEquals(SizeResult.Found(10), c.size("/a.bin")); assertEquals(SizeResult.NotFound, c.size("/missing"))
        srv.supportsSize = false; assertEquals(SizeResult.Unsupported, client().size("/a.bin"))
    }
    @Test fun listRenameDeleteAndRetrieve() {
        srv.dirs += "/d"; srv.put("/d/x.bin", byteArrayOf(1, 2, 3, 4)); val c = client()
        assertEquals(listOf("x.bin"), c.list("/d").map { it.name })
        c.rename("/d/x.bin", "/d/y.bin"); assertNull(srv.get("/d/x.bin")); assertContentEquals(byteArrayOf(1, 2, 3, 4), srv.get("/d/y.bin"))
        assertContentEquals(byteArrayOf(3, 4), c.retrieve("/d/y.bin", 2))
        c.delete("/d/y.bin"); assertNull(srv.get("/d/y.bin"))
        assertFailsWith<FtpCommandException> { c.delete("/d/y.bin") }
    }
    @Test fun uploadWithRestResumesAtAnOffset() {
        srv.dirs += "/d"; srv.put("/d/f.part", Pattern.bytes(0, 1000)); val c = client()
        val up = c.beginStore("/d/f.part", FtpClient.StoreMode.STOR, 1000); up.out.write(Pattern.bytes(1000, 500)); up.finish()
        assertContentEquals(Pattern.bytes(0, 1500), srv.get("/d/f.part"))
    }
    @Test fun serverWithoutRestRefusesAndWeSeeIt() {
        srv.supportsRest = false; srv.dirs += "/d"; val c = client()
        assertEquals("REST", assertFailsWith<FtpCommandException> { c.beginStore("/d/f", FtpClient.StoreMode.STOR, 10) }.command)
    }
    @Test fun appendMode() {
        srv.dirs += "/d"; srv.put("/d/f", byteArrayOf(1, 2)); val c = client()
        val up = c.beginStore("/d/f", FtpClient.StoreMode.APPE); up.out.write(byteArrayOf(3)); up.finish()
        assertContentEquals(byteArrayOf(1, 2, 3), srv.get("/d/f"))
    }
    @Test fun freeSpaceWhenAvailable() { srv.avbl = 12345; assertEquals(12345L, client().freeSpace("/")); srv.avbl = null; assertNull(client().freeSpace("/")) }
    @Test fun commandInjectionIsBlockedBeforeAnythingIsSent() {
        val c = client(); val before = srv.commands.size
        assertFailsWith<IllegalArgumentException> { c.cwd("/a\r\nDELE /important") }
        assertFailsWith<IllegalArgumentException> { c.delete("/x\nQUIT") }
        assertEquals(before, srv.commands.size)
    }
    @Test fun theAddressInPasvRepliesIsIgnored() {
        srv.pasvIp = "203.0.113.9"   // a hostile or misconfigured server pointing us somewhere else
        srv.dirs += "/d"; srv.put("/d/x", byteArrayOf(1))
        assertEquals(listOf("x"), client().list("/d").map { it.name })
    }
}

class HttpSourceTests {
    private lateinit var web: MockHttpServer
    private val http = HttpSource(UrlPolicy(allowPrivateHosts = true), 2000, 5000)
    @Before fun up() { web = MockHttpServer(size = 1_000_000) }
    @After fun down() { web.close() }

    @Test fun rangeCapableServer() {
        val i = http.preflight("${web.base}/file.bin")
        assertEquals(206, i.status); assertTrue(i.acceptsRanges); assertEquals(1_000_000L, i.contentLength)
        assertEquals("file.bin", i.fileName); assertEquals("\"v1\"", i.strongEtag); assertTrue(i.hasResumeValidator)
    }
    @Test fun serverThatIgnoresRanges() {
        web.honorRange = false
        val i = http.preflight("${web.base}/file.bin")
        assertFalse(i.acceptsRanges); assertEquals(1_000_000L, i.contentLength); assertTrue(i.notes.any { "single stream" in it })
    }
    @Test fun missingValidatorsAreReported() {
        web.etag = null; web.lastModified = null
        val i = http.preflight("${web.base}/file.bin"); assertFalse(i.hasResumeValidator); assertTrue(i.notes.any { "restart from zero" in it })
    }
    @Test fun weakEtagIsNotUsedForResume() {
        web.etag = "W/\"weak\""; web.lastModified = null
        assertFalse(http.preflight("${web.base}/file.bin").hasResumeValidator)
    }
    @Test fun statusCodesMapToUsefulErrors() {
        assertEquals(SourceErrorKind.AUTH_REQUIRED, assertFailsWith<SourceException> { http.preflight("${web.base}/auth") }.kind)
        assertEquals(SourceErrorKind.GONE, assertFailsWith<SourceException> { http.preflight("${web.base}/gone") }.kind)
        val dead = java.net.ServerSocket(0).use { it.localPort }
        assertEquals(SourceErrorKind.UNREACHABLE, assertFailsWith<SourceException> { http.preflight("http://127.0.0.1:$dead/x") }.kind)
    }
    @Test fun redirectsAreFollowedAndLoopsStop() {
        val i = http.preflight("${web.base}/redir"); assertTrue(i.finalUrl.endsWith("/file.bin")); assertEquals(1, i.redirects.size)
        assertEquals(SourceErrorKind.REDIRECT_LOOP, assertFailsWith<SourceException> { http.preflight("${web.base}/loop") }.kind)
    }
    @Test fun malformedContentRangeIsRejected() {
        web.badContentRange = true
        assertEquals(SourceErrorKind.BAD_RESPONSE, assertFailsWith<SourceException> { http.preflight("${web.base}/file.bin") }.kind)
    }
    @Test fun webPagesAreNotMistakenForFiles() {
        assertEquals(SourceErrorKind.NOT_A_FILE, assertFailsWith<SourceException> { http.preflight("${web.base}/page") }.kind)
        val i = http.preflight("${web.base}/page-link")             // landing page with a link to a media file
        assertTrue(i.finalUrl.endsWith("/file.bin")); assertEquals(1_000_000L, i.contentLength)
    }
    @Test fun filenameFromContentDispositionAndOverride() {
        assertEquals("My Game v1.pkg", http.preflight("${web.base}/named").fileName)
        assertEquals("custom.pkg", http.preflight("${web.base}/named", fileNameOverride = "custom.pkg").fileName)
        assertEquals("evil.pkg", http.preflight("${web.base}/named", fileNameOverride = "../../evil.pkg").fileName)
    }
    @Test fun privateHostsAndOddSchemesAreRefused() {
        val strict = HttpSource(UrlPolicy(allowPrivateHosts = false), 2000, 5000)
        assertFailsWith<UrlRejectedException> { strict.preflight("${web.base}/file.bin") }
        assertFailsWith<UrlRejectedException> { http.preflight("ftp://example.com/x") }
        assertFailsWith<UrlRejectedException> { http.preflight("not a url") }
    }
    @Test fun customHeadersCannotInjectOrOverrideManagedOnes() {
        assertFailsWith<IllegalArgumentException> { http.preflight("${web.base}/file.bin", mapOf("X-A" to "v\r\nInjected: 1")) }
        assertFailsWith<IllegalArgumentException> { http.preflight("${web.base}/file.bin", mapOf("Range" to "bytes=5-")) }
        http.preflight("${web.base}/file.bin", mapOf("Authorization" to "Bearer t"))
    }
    @Test fun openRangeReturnsExactlyTheRequestedBytes() {
        val i = http.preflight("${web.base}/file.bin")
        http.openRange(i, emptyMap(), 1000, 1999).use { assertContentEquals(Pattern.bytes(1000, 1000), it.input.readBytes()) }
    }
    @Test fun aChangedFileIsDetectedViaIfRange() {
        val i = http.preflight("${web.base}/file.bin"); web.etag = "\"v2\""
        assertFailsWith<SourceChangedException> { http.openRange(i, emptyMap(), 10, 99) }
    }
    @Test fun aServerThatStopsHonouringRangesIsDetected() {
        web.etag = null; web.lastModified = null
        val i = http.preflight("${web.base}/file.bin"); web.honorRange = false
        assertFailsWith<RangeIgnoredException> { http.openRange(i, emptyMap(), 10, 99) }
    }
    @Test fun aShrunkenFileIsDetected() {
        val i = http.preflight("${web.base}/file.bin"); web.close(); web = MockHttpServer(size = 999_000)
        val moved = i.copy(finalUrl = "${web.base}/file.bin")
        assertFailsWith<SourceChangedException> { http.openRange(moved, emptyMap(), 10, 99) }
    }
}
