package com.directstream.ps5.core.util

/** File-name and path hygiene. Everything that ends up in an FTP command goes through here. */
object Names {
    private val ILLEGAL = Regex("""[\u0000-\u001f\u007f:*?"<>|\\/]""")
    private const val MAX_NAME_BYTES = 200

    fun sanitizeFileName(raw: String?, fallback: String = "download.bin"): String {
        var name = raw.orEmpty()
        name = name.substringAfterLast('/').substringAfterLast('\\')
        name = ILLEGAL.replace(name, "_").trim().trim('.', ' ')
        if (name.isEmpty() || name == "." || name == "..") return fallback
        return truncateKeepingExtension(name)
    }

    private fun truncateKeepingExtension(name: String): String {
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot in 1 until name.length && name.length - dot <= 12) name.substring(dot) else ""
        var stem = name.removeSuffix(ext)
        while ((stem + ext).toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES && stem.isNotEmpty())
            stem = stem.dropLast(1)
        return stem + ext
    }

    /** Throws if the string could break out of an FTP command line. */
    fun requireSafeFtpArgument(arg: String): String {
        require(arg.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Illegal control character in path" }
        return arg
    }

    /** Normalises an absolute remote directory; rejects traversal and control characters. */
    fun normalizeRemoteDir(path: String): String {
        requireSafeFtpArgument(path)
        require(path.startsWith("/")) { "Remote directory must be absolute (start with /)" }
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.none { it == ".." }) { "Remote directory must not contain .." }
        return "/" + parts.joinToString("/")
    }

    fun joinRemote(dir: String, name: String): String {
        val d = normalizeRemoteDir(dir)
        val n = sanitizeFileName(name)
        return if (d == "/") "/$n" else "$d/$n"
    }

    /** For archive entries: relative, no traversal (zip-slip), each segment sanitised. */
    fun sanitizeRelativePath(raw: String): String {
        val segments = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        require(segments.none { it == ".." }) { "Path traversal rejected: $raw" }
        return segments.joinToString("/") { sanitizeFileName(it, "_") }
    }
}
