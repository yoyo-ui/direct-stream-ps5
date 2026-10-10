package com.directstream.ps5.core.http

import com.directstream.ps5.core.net.UrlPolicy
import com.directstream.ps5.core.net.UrlRejectedException
import com.directstream.ps5.core.resolver.Resolver
import com.directstream.ps5.core.util.Names
import com.directstream.ps5.core.util.Redaction
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder

enum class SourceErrorKind { UNREACHABLE, HTTP_ERROR, AUTH_REQUIRED, GONE, RATE_LIMITED, REDIRECT_LOOP, BAD_RESPONSE, NOT_A_FILE }

class SourceException(val kind: SourceErrorKind, message: String, val status: Int? = null) : IOException(message)
class SourceChangedException(message: String) : IOException(message)
class RangeIgnoredException(message: String) : IOException(message)

data class SourceInfo(
    val originalUrl: String,
    val finalUrl: String,
    val status: Int,
    val fileName: String,
    val contentLength: Long?,
    val acceptsRanges: Boolean,
    val etag: String?,
    val lastModified: String?,
    val contentType: String?,
    val redirects: List<String>,
    val notes: List<String>
) {
    /** Validator usable with If-Range: strong ETags only (weak ones may not be used for byte ranges). */
    val strongEtag: String? get() = etag?.takeIf { !it.startsWith("W/") }
    val validator: String? get() = strongEtag ?: lastModified
    val hasResumeValidator: Boolean get() = validator != null

    /** Honest description of how much we can check about this source. */
    val verificationNote: String get() = when {
        contentLength == null -> "Server did not report a size; completeness can't be checked against the source."
        else -> "Size known (${contentLength} bytes); no source checksum is available over HTTP."
    }
}

/**
 * Minimal HTTP client on HttpURLConnection. Redirects are followed by hand so every hop passes through
 * [UrlPolicy], and credentials are dropped when a redirect changes host.
 */
class HttpSource(
    private val policy: UrlPolicy = UrlPolicy(),
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    
    private val maxRedirects: Int = 5
) {
    private val forbiddenHeaders = setOf("host", "range", "if-range", "content-length", "connection", "transfer-encoding")

    private class Opened(val conn: HttpURLConnection, val finalUrl: URL, val hops: List<String>)

    fun validateHeaders(headers: Map<String, String>) {
        for ((k, v) in headers) {
            require(k.isNotBlank() && k.none { it.isWhitespace() || it == ':' || it < ' ' }) { "Invalid header name" }
            require(v.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Invalid header value" }
            require(k.lowercase() !in forbiddenHeaders) { "Header '$k' is managed by the app" }
        }
    }

    private fun open(url: String, headers: Map<String, String>, extra: Map<String, String>): Opened {
        validateHeaders(headers)
        var current = try { URL(url) } catch (e: Exception) { throw UrlRejectedException("Not a valid URL") }
        policy.validateInitial(current)
        val origin = current
        val hops = mutableListOf<String>()
        repeat(maxRedirects + 1) {
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            // Same defaults as the original engine: browser-style headers, identity encoding (ranges are
            // meaningless on gzip bodies) and a host-appropriate Referer.
            Resolver.requestHeaders(current.toString()).forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setRequestProperty("Connection", "close")
            val sameHost = current.host.equals(origin.host, true) && current.port == origin.port
            if (sameHost) headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            extra.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = try { conn.responseCode } catch (e: IOException) {
                conn.disconnect()
                throw SourceException(SourceErrorKind.UNREACHABLE, "Cannot reach ${current.host}: ${e.message}")
            }
            if (code in intArrayOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw SourceException(SourceErrorKind.BAD_RESPONSE, "Redirect without Location", code)
                val next = try { URL(current, loc) } catch (e: Exception) { throw SourceException(SourceErrorKind.BAD_RESPONSE, "Bad redirect target", code) }
                policy.validateRedirect(current, next)
                hops += Redaction.redactUrl(next.toString())
                current = next
            } else return Opened(conn, current, hops)
        }
        throw SourceException(SourceErrorKind.REDIRECT_LOOP, "Too many redirects (more than $maxRedirects)")
    }

    private fun failForStatus(code: Int, conn: HttpURLConnection) {
        val kind = when (code) {
            401, 403, 407 -> SourceErrorKind.AUTH_REQUIRED
            404, 410 -> SourceErrorKind.GONE
            429, 503 -> SourceErrorKind.RATE_LIMITED
            else -> SourceErrorKind.HTTP_ERROR
        }
        val msg = when (kind) {
            SourceErrorKind.AUTH_REQUIRED -> "The server refused access (HTTP $code). A login, token or password may be required."
            SourceErrorKind.GONE -> "The file was not found or the link has expired (HTTP $code)."
            SourceErrorKind.RATE_LIMITED -> "The server is rate limiting or busy (HTTP $code). Try again later."
            else -> "Unexpected response from server (HTTP $code)."
        }
        conn.disconnect()
        throw SourceException(kind, msg, code)
    }

    /** Probes the link with `Range: bytes=0-0` (works where HEAD is blocked) and reports what the server supports. */
    fun preflight(url: String, headers: Map<String, String> = emptyMap(), fileNameOverride: String? = null): SourceInfo =
        preflight(Resolver.preResolveUrl(url), headers, fileNameOverride, 0, url)

    private fun preflight(url: String, headers: Map<String, String>, fileNameOverride: String?, depth: Int, original: String): SourceInfo {
        val o = open(url, headers, mapOf("Range" to "bytes=0-0"))
        val conn = o.conn
        try {
            val code = conn.responseCode
            val notes = mutableListOf<String>()
            val ctype = conn.contentType.orEmpty().lowercase()
            if (code in 200..299 && "text/html" in ctype) {
                // A landing page, not a file. Try the host-specific link extraction (same as the original), else explain.
                if (depth < 3) {
                    val html = runCatching { conn.inputStream.use { readUpTo(it, 256 * 1024) } }.getOrDefault("")
                    val resolved = Resolver.extractDownloadLinkFromHtml(o.finalUrl.toString(), html)
                    if (resolved != null && resolved != url && resolved != o.finalUrl.toString())
                        return preflight(resolved, headers, fileNameOverride, depth + 1, original)
                }
                throw SourceException(SourceErrorKind.NOT_A_FILE,
                    Resolver.captchaHint(url) ?: "This is a web page, not a downloadable file. Paste the direct download link.", code)
            }
            val enc = conn.getHeaderField("Content-Encoding")
            if (code in 200..299 && !enc.isNullOrBlank() && !enc.equals("identity", true))
                throw SourceException(SourceErrorKind.BAD_RESPONSE, "The server ignored the request for uncompressed bytes.", code)
            var length: Long? = null
            var ranges = false
            when (code) {
                206 -> {
                    val cr = RangeParser.parseContentRange(conn.getHeaderField("Content-Range"))
                        ?: throw SourceException(SourceErrorKind.BAD_RESPONSE, "Server sent a malformed Content-Range header", code)
                    if (cr.start != 0L) throw SourceException(SourceErrorKind.BAD_RESPONSE, "Server returned the wrong byte range", code)
                    length = cr.total
                    ranges = true
                    if (length == null) notes += "Server accepts ranges but did not report the total size."
                }
                200 -> {
                    length = conn.getHeaderFieldLong("Content-Length", -1).takeIf { it >= 0 }
                    ranges = false
                    notes += "Server ignores range requests; a single stream will be used."
                }
                416 -> {   // empty file, or server refuses 0-0
                    length = RangeParser.parseUnsatisfiedTotal(conn.getHeaderField("Content-Range"))
                    if (length != 0L) failForStatus(code, conn)
                    ranges = false
                }
                else -> failForStatus(code, conn)
            }
            val etag = conn.getHeaderField("ETag")
            val lm = conn.getHeaderField("Last-Modified")
            if (etag != null && etag.startsWith("W/")) notes += "Server only offers a weak ETag; it can't be used to guard resumes."
            if (etag == null && lm == null) notes += "No ETag or Last-Modified: resuming after an interruption will restart from zero."
            if (o.hops.isNotEmpty()) notes += "Followed ${o.hops.size} redirect(s)."
            val name = Names.sanitizeFileName(fileNameOverride?.takeIf { it.isNotBlank() }
                ?: Resolver.parseFilenameFromHeaders(
                    mapOf("Content-Disposition" to conn.getHeaderField("Content-Disposition").orEmpty()), o.finalUrl.toString())
                    .takeIf { it.isNotEmpty() }
                ?: fileNameFromDisposition(conn.getHeaderField("Content-Disposition"))
                ?: fileNameFromUrl(o.finalUrl))
            return SourceInfo(Redaction.redactUrl(original), o.finalUrl.toString(), code, name, length, ranges, etag, lm,
                conn.contentType, o.hops, notes)
        } finally { conn.disconnect() }
    }

    /** A body stream that closes its connection with it. */
    class Body(val input: InputStream, private val conn: HttpURLConnection, val expectedLength: Long?) : Closeable {
        override fun close() { try { input.close() } catch (_: Exception) {}; conn.disconnect() }
        fun disconnectNow() = conn.disconnect()
    }

    /**
     * Opens bytes [start, endInclusive] (or to EOF when end is null). Verifies Content-Range, and that the
     * resource is still the one that was probed. Throws RangeIgnoredException / SourceChangedException.
     */
    fun openRange(info: SourceInfo, headers: Map<String, String>, start: Long, endInclusive: Long?): Body {
        val rangeHeader = "bytes=$start-" + (endInclusive?.toString() ?: "")
        val extra = mutableMapOf("Range" to rangeHeader)
        info.validator?.let { extra["If-Range"] = it }
        val o = open(info.finalUrl, headers, extra)
        val conn = o.conn
        val code = conn.responseCode
        if (code == 200) {
            conn.disconnect()
            if (start == 0L && endInclusive == null) return openFull(info, headers)
            throw if (info.validator != null) SourceChangedException("The file changed on the server (If-Range mismatch) or ranges stopped working")
            else RangeIgnoredException("The server ignored the Range header")
        }
        if (code != 206) failForStatus(code, conn)
        val cr = RangeParser.parseContentRange(conn.getHeaderField("Content-Range"))
        if (cr == null) { conn.disconnect(); throw IOException("Malformed Content-Range header from server") }
        if (cr.start != start || (endInclusive != null && cr.endInclusive != endInclusive)) {
            conn.disconnect(); throw IOException("Server returned bytes ${cr.start}-${cr.endInclusive}, wanted $start-$endInclusive")
        }
        if (info.contentLength != null && cr.total != null && cr.total != info.contentLength) {
            conn.disconnect(); throw SourceChangedException("The file size changed on the server")
        }
        val et = conn.getHeaderField("ETag")
        if (info.strongEtag != null && et != null && et != info.etag) {
            conn.disconnect(); throw SourceChangedException("The file's ETag changed on the server")
        }
        return Body(conn.inputStream, conn, cr.length)
    }

    fun openFull(info: SourceInfo, headers: Map<String, String>): Body {
        val o = open(info.finalUrl, headers, emptyMap())
        val conn = o.conn
        val code = conn.responseCode
        if (code != 200) failForStatus(code, conn)
        val len = conn.getHeaderFieldLong("Content-Length", -1).takeIf { it >= 0 }
        if (info.contentLength != null && len != null && len != info.contentLength) {
            conn.disconnect(); throw SourceChangedException("The file size changed on the server")
        }
        return Body(conn.inputStream, conn, len)
    }

    companion object {
        /** Reads at most [max] bytes as text (InputStream.readNBytes doesn't exist on Android 10-12). */
        internal fun readUpTo(input: InputStream, max: Int): String {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() < max) {
                val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        private val FILENAME_STAR = Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
        private val FILENAME = Regex("""filename\s*=\s*("([^"]*)"|[^;]+)""", RegexOption.IGNORE_CASE)

        fun fileNameFromDisposition(h: String?): String? {
            if (h.isNullOrBlank()) return null
            FILENAME_STAR.find(h)?.let {
                val cs = runCatching { charset(it.groupValues[1].ifBlank { "UTF-8" }) }.getOrDefault(Charsets.UTF_8)
                return runCatching { URLDecoder.decode(it.groupValues[2].trim().replace("+", "%2B"), cs.name()) }.getOrNull()
            }
            FILENAME.find(h)?.let { return it.groupValues[2].ifEmpty { it.groupValues[1].trim() } }
            return null
        }

        fun fileNameFromUrl(url: URL): String? {
            val seg = url.path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
            return runCatching { URLDecoder.decode(seg.replace("+", "%2B"), "UTF-8") }.getOrDefault(seg)
        }
    }
}
