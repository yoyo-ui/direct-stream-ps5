package com.directstream.ps5.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Deterministic test payload: byte i of the "file" is a pure function of i. */
object Pattern {
    fun byteAt(i: Long): Byte = ((i * 31 + (i shr 8) * 17 + (i shr 20) * 5) and 0xff).toByte()
    fun bytes(start: Long, len: Int): ByteArray = ByteArray(len) { byteAt(start + it) }
    fun stream(size: Long, produced: AtomicLong? = null, startAt: Long = 0): InputStream = object : InputStream() {
        var pos = startAt
        override fun read(): Int = if (pos >= size) -1 else byteAt(pos++).toInt() and 0xff
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= size) return -1
            val n = minOf(len.toLong(), size - pos).toInt()
            for (i in 0 until n) b[off + i] = byteAt(pos + i)
            pos += n; produced?.addAndGet(n.toLong()); return n
        }
    }
    fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
}

/** Small in-memory FTP server with switches for every failure we want to provoke. */
class MockFtpServer(private val user: String = "u", private val pass: String = "p") : Closeable {
    class FileData { val buf = ByteArrayOutputStream(); @Synchronized fun bytes(): ByteArray = buf.toByteArray(); @Synchronized fun size(): Int = buf.size() }
    val files = ConcurrentHashMap<String, FileData>()
    val dirs: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { add("/") }
    val commands = CopyOnWriteArrayList<String>()
    val storCount = AtomicInteger()

    @Volatile var supportsRest = true
    @Volatile var supportsSize = true
    @Volatile var supportsAppe = true
    @Volatile var supportsXmd5 = false
    @Volatile var rejectRename = false
    @Volatile var dropStoreAfterBytes: Long? = null      // one-shot: kill the data connection after N bytes
    @Volatile var keepOnlyBytes: Long? = null            // server silently keeps fewer bytes but says 226
    @Volatile var corruptStored = false
    @Volatile var avbl: Long? = null
    @Volatile var pasvIp = "127.0.0.1"
    @Volatile var readDelayMsPer64k = 0L                  // throttle uploads
    @Volatile var readHook: ((Long) -> Unit)? = null

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    val port get() = server.localPort

    init { pool.execute { while (!server.isClosed) try { val c = server.accept(); pool.execute { Session(c).run() } } catch (_: IOException) {} } }

    fun put(path: String, data: ByteArray) { files[path] = FileData().also { it.buf.write(data) } }
    fun get(path: String): ByteArray? = files[path]?.bytes()
    fun listFinal() = files.keys.filter { !it.endsWith(".ps5part") }.sorted()
    fun parts() = files.keys.filter { it.endsWith(".ps5part") }

    override fun close() { runCatching { server.close() }; pool.shutdownNow() }

    private inner class Session(val c: Socket) {
        val rd = c.getInputStream().bufferedReader(Charsets.UTF_8)
        val wr = c.getOutputStream()
        var cwd = "/"; var rest = 0L; var passive: ServerSocket? = null; var rnfr: String? = null; var authed = false; var gotUser = ""
        fun send(s: String) { wr.write((s + "\r\n").toByteArray()); wr.flush() }
        fun abs(p: String) = if (p.startsWith("/")) p else (if (cwd == "/") "/$p" else "$cwd/$p")

        fun run() {
            try {
                send("220 Mock FTP ready")
                while (true) {
                    val line = rd.readLine() ?: break
                    commands += if (line.startsWith("PASS")) "PASS ***" else line
                    val cmd = line.substringBefore(' ').uppercase(); val arg = line.substringAfter(' ', "")
                    if (!handle(cmd, arg)) break
                }
            } catch (_: IOException) {} finally { runCatching { c.close() }; runCatching { passive?.close() } }
        }

        fun handle(cmd: String, arg: String): Boolean {
            when (cmd) {
                "USER" -> { gotUser = arg; send("331 Password required") }
                "PASS" -> if (gotUser == user && arg == pass) { authed = true; send("230 Logged in") } else send("530 Login incorrect")
                "FEAT" -> {
                    val f = buildList { if (supportsSize) add("SIZE"); if (supportsRest) add("REST STREAM"); add("MLSD"); add("EPSV"); if (supportsXmd5) add("XMD5") }
                    send("211-Features:"); f.forEach { send(" $it") }; send("211 End")
                }
                "OPTS" -> send("200 OK")
                "TYPE" -> send("200 Type set")
                "PWD" -> send("257 \"$cwd\" is current directory")
                "CWD" -> { val d = abs(arg).trimEnd('/').ifEmpty { "/" }; if (d in dirs) { cwd = d; send("250 OK") } else send("550 No such directory") }
                "MKD" -> { val d = abs(arg).trimEnd('/'); val parent = d.substringBeforeLast('/').ifEmpty { "/" }
                    if (parent !in dirs) send("550 Parent missing") else { dirs += d; send("257 \"$d\" created") } }
                "SIZE" -> if (!supportsSize) send("502 SIZE not implemented") else files[abs(arg)]?.let { send("213 ${it.bytes().size}") } ?: send("550 No such file")
                "REST" -> if (!supportsRest) send("502 REST not implemented") else { rest = arg.toLong(); send("350 Restarting at $rest") }
                "PASV" -> { passive?.close(); passive = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                    val p = passive!!.localPort; send("227 Entering Passive Mode (${pasvIp.replace('.', ',')},${p / 256},${p % 256})") }
                "EPSV" -> { passive?.close(); passive = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")); send("229 Entering Extended Passive Mode (|||${passive!!.localPort}|)") }
                "LIST", "MLSD" -> {
                    val d = (if (arg.isBlank()) cwd else abs(arg)).trimEnd('/').ifEmpty { "/" }
                    if (d !in dirs) { send("550 No such directory") } else {
                        send("150 Here comes the listing")
                        val ds = passive!!.accept()
                        val prefix = if (d == "/") "/" else "$d/"
                        val sb = StringBuilder()
                        dirs.filter { it != d && it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }.forEach {
                            sb.append(if (cmd == "MLSD") "type=dir;modify=20240102030405; ${it.removePrefix(prefix)}\r\n" else "drwxr-xr-x 2 ftp ftp 0 Jan  2 03:04 ${it.removePrefix(prefix)}\r\n") }
                        files.filter { it.key.startsWith(prefix) && !it.key.removePrefix(prefix).contains('/') }.forEach { (k, v) ->
                            sb.append(if (cmd == "MLSD") "type=file;size=${v.bytes().size};modify=20240102030405; ${k.removePrefix(prefix)}\r\n" else "-rw-r--r-- 1 ftp ftp ${v.bytes().size} Jan  2 03:04 ${k.removePrefix(prefix)}\r\n") }
                        ds.getOutputStream().apply { write(sb.toString().toByteArray()); flush() }; ds.close(); passive?.close()
                        send("226 Done")
                    }
                }
                "STOR", "APPE" -> store(cmd, abs(arg))
                "RNFR" -> if (files.containsKey(abs(arg))) { rnfr = abs(arg); send("350 Ready for RNTO") } else send("550 No such file")
                "RNTO" -> if (rejectRename) send("550 Rename not permitted") else { val f = rnfr; val t = abs(arg)
                    if (f == null) send("503 RNFR first") else { files[t] = files.remove(f)!!; send("250 Renamed") } }
                "DELE" -> if (files.remove(abs(arg)) != null) send("250 Deleted") else send("550 No such file")
                "RMD" -> { dirs.remove(abs(arg)); send("250 Removed") }
                "XMD5" -> if (!supportsXmd5) send("500 Unknown") else files[abs(arg)]?.let { send("250 ${Pattern.md5(it.bytes())}") } ?: send("550 No such file")
                "AVBL" -> avbl?.let { send("213 $it") } ?: send("502 not implemented")
                "RETR" -> files[abs(arg)]?.let { fd ->
                    send("150 Opening data connection")
                    val ds = passive!!.accept(); val all = fd.bytes(); val from = minOf(rest.toInt(), all.size); rest = 0
                    ds.getOutputStream().apply { write(all, from, all.size - from); flush() }; ds.close(); passive?.close()
                    send("226 Transfer complete")
                } ?: send("550 No such file")
                "NOOP" -> send("200 OK")
                "QUIT" -> { send("221 Bye"); return false }
                else -> send("502 Command not implemented")
            }
            return true
        }

        fun store(cmd: String, path: String) {
            storCount.incrementAndGet()
            val parent = path.substringBeforeLast('/').ifEmpty { "/" }
            if (parent !in dirs) { send("550 Directory missing"); return }
            if (cmd == "APPE" && !supportsAppe) { send("502 APPE not implemented"); return }
            val fd = if (cmd == "APPE") files.getOrPut(path) { FileData() } else {
                val old = files[path]
                val nf = FileData(); if (rest > 0 && old != null) nf.buf.write(old.bytes(), 0, minOf(rest.toInt(), old.bytes().size)); files[path] = nf; nf }
            rest = 0
            send("150 Opening data connection")
            val ds = passive!!.accept()
            val ins = ds.getInputStream(); val b = ByteArray(65536); var got = 0L
            val drop = dropStoreAfterBytes; var dropped = false
            while (true) {
                val n = try { ins.read(b) } catch (_: IOException) { -1 }
                if (n < 0) break
                readHook?.invoke(got)
                if (readDelayMsPer64k > 0) Thread.sleep(readDelayMsPer64k)
                val keep = keepOnlyBytes
                val toWrite = if (keep != null) maxOf(0L, minOf(n.toLong(), keep - got)).toInt() else n
                val chunk = if (corruptStored && got == 0L && toWrite > 0) b.copyOf(toWrite).also { it[0] = (it[0] + 1).toByte() } else b.copyOf(toWrite)
                synchronized(fd) { fd.buf.write(chunk) }
                got += n
                if (drop != null && got >= drop) { dropStoreAfterBytes = null; dropped = true; runCatching { ds.setSoLinger(true, 0); ds.close() }; break }
            }
            runCatching { ds.close() }; runCatching { passive?.close() }
            if (dropped) send("426 Connection closed; transfer aborted") else send("226 Transfer complete")
        }
    }
}

/** HTTP origin with range support and switches for misbehaviour. */
class MockHttpServer(val size: Long = 4L shl 20) : Closeable {
    @Volatile var honorRange = true
    @Volatile var etag: String? = "\"v1\""
    @Volatile var lastModified: String? = "Wed, 01 Jan 2025 00:00:00 GMT"
    @Volatile var badContentRange = false
    @Volatile var failFirstBodyAfter: Long? = null             // truncate body once, after N bytes
    @Volatile var changeEtagAfterRequests: Int? = null           // after N GETs with Range, serve a new ETag
    @Volatile var failFirstBodyAfterUsed = false
    val requests = CopyOnWriteArrayList<String>()
    val rangeRequests = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    val base get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        server.createContext("/file.bin") { serveFile(it) }
        server.createContext("/named") { ex -> ex.responseHeaders.add("Content-Disposition", "attachment; filename=\"My Game v1.pkg\""); serveFile(ex) }
        server.createContext("/redir") { ex -> ex.responseHeaders.add("Location", "/file.bin"); ex.sendResponseHeaders(302, -1); ex.close() }
        server.createContext("/loop") { ex -> ex.responseHeaders.add("Location", "/loop"); ex.sendResponseHeaders(302, -1); ex.close() }
        server.createContext("/auth") { ex -> ex.sendResponseHeaders(401, -1); ex.close() }
        server.createContext("/page") { ex -> val b = "<html><body><h1>Welcome</h1></body></html>".toByteArray(); ex.responseHeaders.add("Content-Type", "text/html"); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        server.createContext("/page-link") { ex -> val b = "<html><body><a href=\"/file.bin\">Download</a></body></html>".toByteArray(); ex.responseHeaders.add("Content-Type", "text/html"); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        server.createContext("/gone") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
        server.start()
    }

    private fun serveFile(ex: HttpExchange) {
        requests += "${ex.requestMethod} ${ex.requestURI} Range=${ex.requestHeaders.getFirst("Range")} If-Range=${ex.requestHeaders.getFirst("If-Range")}"
        var tag = etag
        val range = ex.requestHeaders.getFirst("Range")
        if (range != null) {
            val n = rangeRequests.incrementAndGet()
            val after = changeEtagAfterRequests
            if (after != null && n > after) tag = "\"v2\""
        }
        tag?.let { ex.responseHeaders.add("ETag", it) }
        lastModified?.let { ex.responseHeaders.add("Last-Modified", it) }
        ex.responseHeaders.add("Accept-Ranges", if (honorRange) "bytes" else "none")
        val m = if (range != null && honorRange) Regex("""bytes=(\d+)-(\d*)""").find(range) else null
        val ifRange = ex.requestHeaders.getFirst("If-Range")
        val rangeOk = m != null && (ifRange == null || ifRange == tag || ifRange == lastModified)
        if (rangeOk) {
            val s = m!!.groupValues[1].toLong(); val e = m.groupValues[2].toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
            if (s >= size) { ex.responseHeaders.add("Content-Range", "bytes */$size"); ex.sendResponseHeaders(416, -1); ex.close(); return }
            ex.responseHeaders.add("Content-Range", if (badContentRange) "bytes banana" else "bytes $s-$e/$size")
            body(ex, 206, s, e - s + 1)
        } else body(ex, 200, 0, size)
    }

    private fun body(ex: HttpExchange, status: Int, start: Long, len: Long) {
        if (ex.requestMethod == "HEAD") { ex.sendResponseHeaders(status, -1); ex.close(); return }
        ex.sendResponseHeaders(status, len)
        val out = ex.responseBody
        try {
            val cut = failFirstBodyAfter
            val limit = if (cut != null && !failFirstBodyAfterUsed && len > cut) { failFirstBodyAfterUsed = true; cut } else len
            var sent = 0L
            while (sent < limit) { val n = minOf(65536L, limit - sent).toInt(); out.write(Pattern.bytes(start + sent, n)); sent += n }
            out.flush()
            if (limit < len) { ex.close(); return }   // closes with fewer bytes than promised
        } catch (_: IOException) {}
        finally { runCatching { out.close() } }
    }

    override fun close() { server.stop(0) }
}
