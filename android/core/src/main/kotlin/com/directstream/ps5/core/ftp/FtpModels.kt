package com.directstream.ps5.core.ftp

import java.io.IOException

data class FtpConfig(
    val host: String,
    val port: Int = 2121,
    val username: String = "",
    val password: String = "",
    val connectTimeoutMs: Int = 10_000,
    val controlTimeoutMs: Int = 30_000,
    val dataTimeoutMs: Int = 30_000
)

data class FtpResponse(val code: Int, val lines: List<String>) {
    val text: String get() = lines.joinToString("\n")
    val message: String get() = lines.firstOrNull().orEmpty()
    val positiveCompletion get() = code in 200..299
    val positivePreliminary get() = code in 100..199
    val intermediate get() = code in 300..399
    val transient get() = code in 400..499
    val permanent get() = code in 500..599
}

data class FtpEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long?,
    val modified: String?,
    val permissions: String? = null
)

sealed class SizeResult {
    data class Found(val bytes: Long) : SizeResult()
    object NotFound : SizeResult()
    object Unsupported : SizeResult()
}

open class FtpException(message: String, val response: FtpResponse? = null, cause: Throwable? = null) : IOException(message, cause)
class FtpAuthException(message: String, response: FtpResponse?) : FtpException(message, response)
class FtpConnectionException(message: String, cause: Throwable? = null) : FtpException(message, null, cause)
class FtpProtocolException(message: String) : FtpException(message)
class FtpCommandException(val command: String, val reply: FtpResponse) :
    FtpException("$command failed: ${reply.code} ${reply.message}", reply)

object FtpResponseParser {
    private val CODE = Regex("""^(\d{3})([ -])(.*)$""")

    /** Reads one (possibly multi-line, RFC 959) reply using [readLine]. Throws on EOF or malformed lines. */
    fun read(readLine: () -> String?): FtpResponse {
        val first = readLine() ?: throw FtpConnectionException("Connection closed by server")
        val m = CODE.matchEntire(first) ?: throw FtpProtocolException("Malformed reply: ${first.take(80)}")
        val code = m.groupValues[1].toInt()
        val lines = mutableListOf(m.groupValues[3])
        if (m.groupValues[2] == "-") {
            while (true) {
                val l = readLine() ?: throw FtpConnectionException("Connection closed in multi-line reply")
                val e = CODE.matchEntire(l)
                if (e != null && e.groupValues[1].toInt() == code && e.groupValues[2] == " ") { lines += e.groupValues[3]; break }
                lines += if (e != null && e.groupValues[1].toInt() == code) e.groupValues[3] else l.trimStart()
            }
        }
        return FtpResponse(code, lines)
    }

    private val PASV = Regex("""(\d{1,3}),(\d{1,3}),(\d{1,3}),(\d{1,3}),(\d{1,3}),(\d{1,3})""")
    private val EPSV = Regex("""\(\|\|\|(\d{1,5})\|\)""")

    /** Port from a 227 reply. The advertised IP is deliberately ignored by the client. */
    fun parsePasvPort(text: String): Int {
        val m = PASV.find(text) ?: throw FtpProtocolException("Cannot parse PASV reply: ${text.take(80)}")
        val p = m.groupValues[5].toInt() * 256 + m.groupValues[6].toInt()
        if (p !in 1..65535) throw FtpProtocolException("PASV port out of range")
        return p
    }

    fun parseEpsvPort(text: String): Int {
        val m = EPSV.find(text) ?: throw FtpProtocolException("Cannot parse EPSV reply: ${text.take(80)}")
        val p = m.groupValues[1].toInt()
        if (p !in 1..65535) throw FtpProtocolException("EPSV port out of range")
        return p
    }

    fun parseFeatures(r: FtpResponse): Set<String> =
        if (!r.positiveCompletion) emptySet()
        else r.lines.drop(1).map { it.trim().uppercase() }.filter { it.isNotEmpty() && !it.startsWith("END") }.toSet()
}

object FtpListParser {
    private val UNIX = Regex("""^([\-dlbcps][rwxsStT\-]{9}[+.@]?)\s+\d+\s+\S+\s+\S+\s+(\d+)\s+(\w{3}\s+\d{1,2}\s+(?:\d{4}|\d{1,2}:\d{2}))\s+(.+)$""")
    private val UNIX_NOGROUP = Regex("""^([\-dlbcps][rwxsStT\-]{9}[+.@]?)\s+\d+\s+\S+\s+(\d+)\s+(\w{3}\s+\d{1,2}\s+(?:\d{4}|\d{1,2}:\d{2}))\s+(.+)$""")
    private val DOS = Regex("""^(\d{2}-\d{2}-\d{2,4})\s+(\d{1,2}:\d{2}(?:AM|PM)?)\s+(<DIR>|\d+)\s+(.+)$""", RegexOption.IGNORE_CASE)

    fun parseList(lines: List<String>): List<FtpEntry> = lines.mapNotNull { parseListLine(it.trimEnd('\r', '\n')) }
        .filter { it.name != "." && it.name != ".." }

    fun parseListLine(line: String): FtpEntry? {
        if (line.isBlank() || line.startsWith("total ")) return null
        (UNIX.matchEntire(line) ?: UNIX_NOGROUP.matchEntire(line))?.let { m ->
            val perms = m.groupValues[1]
            var name = m.groupValues[4]
            if (perms[0] == 'l') name = name.substringBefore(" -> ")
            return FtpEntry(name, perms[0] == 'd', m.groupValues[2].toLongOrNull(), m.groupValues[3], perms)
        }
        DOS.matchEntire(line)?.let { m ->
            val dir = m.groupValues[3].equals("<DIR>", true)
            return FtpEntry(m.groupValues[4], dir, if (dir) null else m.groupValues[3].toLongOrNull(), "${m.groupValues[1]} ${m.groupValues[2]}")
        }
        return null
    }

    /** MLSD: `type=file;size=12;modify=20240102030405; name` */
    fun parseMlsd(lines: List<String>): List<FtpEntry> = lines.mapNotNull { raw ->
        val line = raw.trimEnd('\r', '\n')
        val sp = line.indexOf(' ')
        if (sp < 0) return@mapNotNull null
        val facts = line.substring(0, sp).split(';').filter { it.contains('=') }
            .associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }
        val name = line.substring(sp + 1)
        val type = facts["type"]?.lowercase()
        if (type == "cdir" || type == "pdir" || name == "." || name == "..") return@mapNotNull null
        FtpEntry(name, type == "dir", facts["size"]?.toLongOrNull(), facts["modify"]?.let(::formatMlsdTime), facts["perm"])
    }

    private fun formatMlsdTime(t: String): String =
        if (t.length >= 14) "${t.substring(0, 4)}-${t.substring(4, 6)}-${t.substring(6, 8)} ${t.substring(8, 10)}:${t.substring(10, 12)}:${t.substring(12, 14)}" else t
}
