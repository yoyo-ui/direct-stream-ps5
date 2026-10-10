package com.directstream.ps5.core.ftp

import com.directstream.ps5.core.util.Names
import com.directstream.ps5.core.util.Redaction
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Plain (unencrypted) FTP client. Blocking, one command at a time: callers must serialize access and
 * run it off the main thread. Passive mode only; the IP in PASV replies is ignored (the control host
 * is used) so a hostile or misconfigured server can't redirect data connections elsewhere.
 * Credentials and file contents travel in clear text.
 */
class FtpClient(private val config: FtpConfig, private val log: (String) -> Unit = {}) {
    @Volatile private var control: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: OutputStream? = null
    @Volatile private var dataSocket: Socket? = null
    var features: Set<String> = emptySet(); private set
    var banner: String = ""; private set
    val isConnected get() = control?.isConnected == true && control?.isClosed == false

    // ---------------------------------------------------------------- connection

    fun connect() {
        close()
        val s = Socket()
        try {
            s.connect(InetSocketAddress(config.host, config.port), config.connectTimeoutMs)
        } catch (e: IOException) {
            runCatching { s.close() }
            throw FtpConnectionException("Cannot connect to ${config.host}:${config.port}: ${e.message}", e)
        }
        s.soTimeout = config.controlTimeoutMs
        s.tcpNoDelay = true
        s.keepAlive = true
        control = s
        input = BufferedInputStream(s.getInputStream())
        output = s.getOutputStream()
        val hello = readResponse()
        if (hello.code != 220) throw FtpCommandException("CONNECT", hello)
        banner = hello.message
    }

    fun login() {
        val user = config.username.ifBlank { "anonymous" }
        val pass = if (config.username.isBlank()) "anonymous@" else config.password
        var r = command("USER $user")
        if (r.intermediate) r = command("PASS $pass")
        if (r.code == 530 || r.code == 430 || r.code == 332) throw FtpAuthException("Login rejected: ${r.message}", r)
        if (!r.positiveCompletion) throw FtpAuthException("Login failed: ${r.code} ${r.message}", r)
        loadFeatures()
        command("TYPE I").also { if (!it.positiveCompletion) throw FtpCommandException("TYPE I", it) }
        if ("UTF8" in features) runCatching { command("OPTS UTF8 ON") }
    }

    private fun loadFeatures() {
        features = try { FtpResponseParser.parseFeatures(command("FEAT")) } catch (e: FtpProtocolException) { emptySet() }
    }

    fun supports(feature: String) = features.any { it == feature.uppercase() || it.startsWith(feature.uppercase() + " ") }

    fun close() {
        runCatching { dataSocket?.close() }; dataSocket = null
        runCatching { control?.close() }; control = null
        input = null; output = null
    }

    /** Safe to call from another thread to unblock a stuck read (used for cancellation). */
    fun abortNow() { runCatching { dataSocket?.close() }; runCatching { control?.close() } }

    fun quit() {
        if (!isConnected) return
        runCatching { command("QUIT") }
        close()
    }

    // ---------------------------------------------------------------- raw protocol

    private fun readLine(): String? {
        val inp = input ?: throw FtpConnectionException("Not connected")
        val buf = ByteArrayOutputStream(80)
        try {
            while (true) {
                val b = inp.read()
                if (b < 0) return if (buf.size() == 0) null else String(buf.toByteArray(), Charsets.UTF_8)
                if (b == '\n'.code) break
                if (b != '\r'.code) buf.write(b)
                if (buf.size() > 16 * 1024) throw FtpProtocolException("Reply line too long")
            }
        } catch (e: SocketTimeoutException) {
            throw FtpConnectionException("Timed out waiting for the server", e)
        } catch (e: IOException) {
            if (e is FtpException) throw e
            throw FtpConnectionException("Connection lost: ${e.message}", e)
        }
        return String(buf.toByteArray(), Charsets.UTF_8)
    }

    fun readResponse(): FtpResponse = FtpResponseParser.read(::readLine).also { log("< ${it.code} ${it.message}") }

    fun command(line: String): FtpResponse {
        Names.requireSafeFtpArgument(line)
        val out = output ?: throw FtpConnectionException("Not connected")
        log("> " + Redaction.redactFtpCommand(line))
        try { out.write((line + "\r\n").toByteArray(Charsets.UTF_8)); out.flush() }
        catch (e: IOException) { throw FtpConnectionException("Connection lost: ${e.message}", e) }
        return readResponse()
    }

    private fun expect(line: String, vararg ok: Int): FtpResponse {
        val r = command(line)
        if (if (ok.isEmpty()) !r.positiveCompletion else r.code !in ok) throw FtpCommandException(line.substringBefore(' '), r)
        return r
    }

    // ---------------------------------------------------------------- navigation / metadata

    fun pwd(): String {
        val r = expect("PWD", 257)
        return Regex(""""((?:[^"]|"")*)"""").find(r.message)?.groupValues?.get(1)?.replace("\"\"", "\"") ?: "/"
    }

    fun cwd(path: String) { expect("CWD ${Names.requireSafeFtpArgument(path)}") }

    fun directoryExists(path: String): Boolean {
        val here = runCatching { pwd() }.getOrNull()
        val r = command("CWD ${Names.requireSafeFtpArgument(path)}")
        if (r.positiveCompletion && here != null) runCatching { command("CWD $here") }
        return r.positiveCompletion
    }

    /** Creates each missing path component. Does not assume any directory is writable. */
    fun makeDirectories(path: String) {
        val norm = Names.normalizeRemoteDir(path)
        if (norm == "/") return
        var cur = ""
        for (seg in norm.split('/').filter { it.isNotEmpty() }) {
            cur += "/$seg"
            if (directoryExists(cur)) continue
            val r = command("MKD $cur")
            if (!r.positiveCompletion && !directoryExists(cur)) throw FtpCommandException("MKD $cur", r)
        }
    }

    fun size(path: String): SizeResult {
        val r = command("SIZE ${Names.requireSafeFtpArgument(path)}")
        return when {
            r.code == 213 -> r.message.trim().toLongOrNull()?.let { SizeResult.Found(it) } ?: SizeResult.Unsupported
            r.code == 550 || r.code == 450 -> SizeResult.NotFound
            else -> SizeResult.Unsupported
        }
    }

    fun list(dir: String): List<FtpEntry> {
        val useMlsd = supports("MLSD")
        val lines = withDataConnection(if (useMlsd) "MLSD ${Names.requireSafeFtpArgument(dir)}" else "LIST ${Names.requireSafeFtpArgument(dir)}") { s ->
            s.getInputStream().bufferedReader(Charsets.UTF_8).readLines()
        }
        return if (useMlsd) FtpListParser.parseMlsd(lines) else FtpListParser.parseList(lines)
    }

    fun fileExists(path: String): Boolean {
        when (val s = size(path)) {
            is SizeResult.Found -> return true
            SizeResult.NotFound -> if (supports("SIZE")) return false
            SizeResult.Unsupported -> {}
        }
        val parent = path.substringBeforeLast('/', "").ifEmpty { "/" }
        val name = path.substringAfterLast('/')
        return runCatching { list(parent).any { it.name == name } }.getOrDefault(false)
    }

    fun rename(from: String, to: String) {
        expect("RNFR ${Names.requireSafeFtpArgument(from)}", 350)
        expect("RNTO ${Names.requireSafeFtpArgument(to)}")
    }

    fun delete(path: String) { expect("DELE ${Names.requireSafeFtpArgument(path)}") }
    fun removeDirectory(path: String) { expect("RMD ${Names.requireSafeFtpArgument(path)}") }

    /** Vendor extension (XMD5 / XSHA1 / XCRC). Returns lowercase hex, or null if unsupported. */
    fun checksum(command: String, path: String): String? {
        val r = command("$command ${Names.requireSafeFtpArgument(path)}")
        return if (r.positiveCompletion) r.message.trim().split(' ').last().lowercase() else null
    }

    // ---------------------------------------------------------------- data connections

    private fun openPassive(): Socket {
        val port = if (supports("EPSV")) {
            val r = command("EPSV")
            if (r.code == 229) FtpResponseParser.parseEpsvPort(r.message) else pasvPort()
        } else pasvPort()
        val s = Socket()
        try { s.connect(InetSocketAddress(config.host, port), config.connectTimeoutMs) }
        catch (e: IOException) { runCatching { s.close() }; throw FtpConnectionException("Cannot open data connection to ${config.host}:$port: ${e.message}", e) }
        s.soTimeout = config.dataTimeoutMs
        s.tcpNoDelay = false
        runCatching { s.sendBufferSize = 512 * 1024; s.receiveBufferSize = 512 * 1024 }
        dataSocket = s
        return s
    }

    private fun pasvPort(): Int = FtpResponseParser.parsePasvPort(expect("PASV", 227).message)

    private fun <T> withDataConnection(cmd: String, body: (Socket) -> T): T {
        val s = openPassive()
        try {
            val r = command(cmd)
            if (!r.positivePreliminary) throw FtpCommandException(cmd.substringBefore(' '), r)
            val result = body(s)
            runCatching { s.close() }; dataSocket = null
            val done = readResponse()
            if (!done.positiveCompletion) throw FtpCommandException(cmd.substringBefore(' '), done)
            return result
        } catch (e: Throwable) {
            runCatching { s.close() }; dataSocket = null
            throw e
        }
    }

    /** Downloads (the rest of) a file. Used for small files such as the extractor log. */
    fun retrieve(path: String, restartOffset: Long = 0, maxBytes: Int = 4 shl 20): ByteArray {
        Names.requireSafeFtpArgument(path)
        val s = openPassive()
        try {
            if (restartOffset > 0) { val r = command("REST $restartOffset"); if (r.code != 350) throw FtpCommandException("REST", r) }
            val r = command("RETR $path")
            if (!r.positivePreliminary) throw FtpCommandException("RETR", r)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            val ins = s.getInputStream()
            while (out.size() < maxBytes) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n) }
            runCatching { s.close() }; dataSocket = null
            val done = readResponse()
            if (!done.positiveCompletion) throw FtpCommandException("RETR", done)
            return out.toByteArray()
        } catch (e: Throwable) { runCatching { s.close() }; dataSocket = null; throw e }
    }

    /** Uploads a small in-memory file (e.g. config.ini). */
    fun storeBytes(path: String, data: ByteArray) {
        val up = beginStore(path)
        try { up.out.write(data); up.finish() } catch (e: Throwable) { up.abort(); throw e }
    }

    /** Free space in bytes if the server can say (AVBL or SITE FREESPACE), otherwise null. */
    fun freeSpace(dir: String): Long? {
        Names.requireSafeFtpArgument(dir)
        runCatching {
            val r = command("AVBL $dir")
            if (r.code == 213) r.message.trim().toLongOrNull()?.let { return it }
        }
        runCatching {
            val r = command("SITE FREESPACE $dir")
            val m = Regex("""(\d+)\s*(?:bytes|b)?\s*free""", RegexOption.IGNORE_CASE).find(r.message) ?: Regex("""^(\d+)""").find(r.message)
            if (r.positiveCompletion && m != null) return m.groupValues[1].toLong()
        }
        return null
    }

    enum class StoreMode { STOR, APPE }

    /** An open upload. Write bytes with [out], then call [finish]. [abort] on error. */
    inner class Upload internal constructor(private val socket: Socket) {
        val out: OutputStream = socket.getOutputStream()
        fun finish(): FtpResponse {
            try { out.flush() } finally { runCatching { socket.close() }; dataSocket = null }
            val r = readResponse()
            if (!r.positiveCompletion) throw FtpCommandException("STOR", r)
            return r
        }
        fun abort() {
            runCatching { socket.close() }; dataSocket = null
            // A server may or may not send a final reply after we drop the data channel; don't wait long for it.
            runCatching { control?.soTimeout = 2000; readResponse() }
            runCatching { control?.soTimeout = config.controlTimeoutMs }
        }
    }

    /**
     * Starts an upload. With [restartOffset] > 0 and STOR, `REST <n>` is sent first (server must accept it);
     * with APPE the server appends to the existing file. Throws if the server refuses.
     */
    fun beginStore(path: String, mode: StoreMode = StoreMode.STOR, restartOffset: Long = 0): Upload {
        Names.requireSafeFtpArgument(path)
        val s = openPassive()
        try {
            if (mode == StoreMode.STOR && restartOffset > 0) {
                val r = command("REST $restartOffset")
                if (r.code != 350) throw FtpCommandException("REST", r)
            }
            val r = command("${mode.name} $path")
            if (!r.positivePreliminary) throw FtpCommandException(mode.name, r)
            return Upload(s)
        } catch (e: Throwable) { runCatching { s.close() }; dataSocket = null; throw e }
    }
}
