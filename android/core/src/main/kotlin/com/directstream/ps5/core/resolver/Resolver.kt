package com.directstream.ps5.core.resolver

import java.net.URI
import java.net.URLDecoder

/**
 * Kotlin port of the link-resolution helpers in the original project's `resolver.py` (v2.9.0).
 * Ported: URL rewriting and page-link extraction for the hosts the README documents as supported
 * (MediaFire, PixelDrain, Google Drive, Internet Archive, GoFile, 1fichier, BuzzHeavier, Send.cm) plus the
 * generic page heuristics. NOT ported: the other site-specific handlers in resolver.py, and the Rootz
 * network round-trip. Scraping HTML is inherently fragile: when it fails the user must paste a direct link.
 */
object Resolver {
    private const val MEDIA_EXT = "(?:pkg|ffpfsc|exfat|ufs|bin|iso|img|tar|zip|rar|7z|001)"
    private val PRIMARY_EXTS = listOf(".ffpfsc", ".exfat", ".ufs", ".pkg", ".iso", ".bin")
    private val I = RegexOption.IGNORE_CASE

    const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    fun preResolveUrl(input: String): String {
        val url = input.trim()
        if (url.isEmpty()) return url

        Regex("""^https?://(?:www\.)?pixeldrain\.com/u/([a-zA-Z0-9_-]+)""", I).find(url)?.let {
            return "https://pixeldrain.com/api/file/${it.groupValues[1]}"
        }
        Regex("""drive\.google\.com/(?:file/d/|open\?id=|uc\?id=)([a-zA-Z0-9_-]+)""", I).find(url)?.let {
            if (!url.contains("export=download")) return "https://drive.google.com/uc?export=download&id=${it.groupValues[1]}"
        }
        Regex("""^https?://(?:www\.)?archive\.org/details/([^/]+)/([^?#]+)""", I).find(url)?.let {
            return "https://archive.org/download/${it.groupValues[1]}/${it.groupValues[2]}"
        }
        Regex("""^https?://(?:www\.)?(buzzheavier\.com|bzzhr\.to)/(?:f/)?([a-zA-Z0-9_-]+)/?$""", I).find(url)?.let {
            return "https://${it.groupValues[1]}/${it.groupValues[2]}/download"
        }
        return url
    }

    private fun clean(s: String) = s.replace("&amp;", "&")
    private fun join(base: String, ref: String): String = try { URI(base).resolve(ref).toString() } catch (e: Exception) { ref }
    private fun domainOf(url: String): String = try { URI(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }

    fun extractDownloadLinkFromHtml(pageUrl: String, html: String): String? {
        if (html.isEmpty()) return null
        val domain = domainOf(pageUrl)

        if ("mediafire.com" in domain) {
            val patterns = listOf(
                """id=["']downloadButton["'][^>]*href=["'](https?://[^"']+)["']""",
                """aria-label=["']Download file["'][^>]*href=["'](https?://[^"']+)["']""",
                """href=["'](https?://download[^"']+\.mediafire\.com/[^"']+)["']""",
                """href=["'](https?://[^"']+)["'][^>]*id=["']downloadButton["']"""
            )
            for (p in patterns) Regex(p, I).find(html)?.let { return clean(it.groupValues[1]) }
        }

        if ("archive.org" in domain) {
            var links = Regex("""href=["']((?:https?://(?:www\.)?archive\.org)?/download/[^"']+\.$MEDIA_EXT(?:\?[^"']*)?)["']""", I)
                .findAll(html).map { it.groupValues[1] }.toList()
            if (links.isEmpty()) links = Regex("""<a[^>]*href=["']([^"']+\.$MEDIA_EXT(?:\?[^"']*)?)["']""", I).findAll(html).map { it.groupValues[1] }.toList()
            if (links.isNotEmpty()) {
                val primary = links.firstOrNull { l -> PRIMARY_EXTS.any { l.lowercase().substringBefore('?').endsWith(it) } }
                return join(pageUrl, clean(primary ?: links[0]))
            }
        }

        if ("buzzheavier.com" in domain || "bzzhr.to" in domain) {
            Regex("""href=["'](https?://[a-zA-Z0-9.-]*buzzheavier\.com/[^"']+)["']""").find(html)?.let { return clean(it.groupValues[1]) }
            Regex("""(?:hx-get|hx-post|href)=["']([^"']*/download[^"']*)["']""").find(html)?.let { return join(pageUrl, clean(it.groupValues[1])) }
        }

        if ("1fichier.com" in domain) {
            Regex("""href=["'](https?://[a-zA-Z0-9.-]+\.1fichier\.com/[^"']+)["']""").find(html)?.let { return clean(it.groupValues[1]) }
            Regex("""<a[^>]*class=["'][^"']*btn-orange[^"']*["'][^>]*href=["'](https?://[^"']+)["']""").find(html)?.let { return clean(it.groupValues[1]) }
        }

        if ("send.cm" in domain) {
            Regex("""href=["'](https?://[a-zA-Z0-9.-]*send\.cm/[^"']+\.$MEDIA_EXT(?:\?[^"']*)?)["']""", I).find(html)?.let { return clean(it.groupValues[1]) }
        }

        if ("google.com" in domain || "googleusercontent.com" in domain) {
            Regex("""id=["']uc-download-link["'][^>]*href=["']([^"']+)["']""", I).find(html)?.let { return join(pageUrl, clean(it.groupValues[1])) }
            val form = Regex("""<form[^>]*action=["']([^"']+)["'][^>]*id=["']download-form["']""", I).find(html)
                ?: Regex("""<form[^>]*action=["']([^"']+)["']""", I).find(html)
            if (form != null) {
                val action = join(pageUrl, clean(form.groupValues[1]))
                val inputs = Regex("""<input[^>]*name=["']([^"']+)["'][^>]*value=["']([^"']*)["']""", I).findAll(html)
                    .associate { it.groupValues[1] to it.groupValues[2] }
                if ("confirm" in inputs || "id" in inputs) {
                    val q = inputs.entries.joinToString("&") { (k, v) -> java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8") }
                    return action + (if ("?" in action) "&" else "?") + q
                }
            }
        }

        if ("gofile.io" in domain) {
            Regex(""""link":\s*"(https?://store[^"]+)"""").find(html)?.let { return it.groupValues[1].replace("\\/", "/") }
        }

        // ---- generic heuristics
        Regex("""<meta[^>]*http-equiv=["']refresh["'][^>]*content=["'][^"']*url=([^"'>\s]+)""", I).find(html)?.let {
            val target = it.groupValues[1].trim().trim('\'', '"')
            if (target.startsWith("http") || target.startsWith("/")) return join(pageUrl, target)
        }
        var game = Regex("""<a[^>]*href=["'](https?://[^"']+\.$MEDIA_EXT(?:\?[^"']*)?)["']""", I).findAll(html).map { it.groupValues[1] }.toList()
        if (game.isEmpty()) {
            game = Regex("""<a[^>]*href=["'](/[^"']+\.$MEDIA_EXT(?:\?[^"']*)?)["']""", I).findAll(html).map { join(pageUrl, it.groupValues[1]) }.toList()
        }
        if (game.size == 1) return game[0]
        if (game.size > 1) {
            val primary = game.filter { l -> PRIMARY_EXTS.any { l.lowercase().substringBefore('?').endsWith(it) } }
            return primary.firstOrNull() ?: game[0]
        }
        Regex("""<a[^>]*download(?:\s*=\s*["'][^"']*["'])?[^>]*href=["'](https?://[^"']+)["']""", I).find(html)?.let {
            return join(pageUrl, clean(it.groupValues[1]))
        }
        return null
    }

    private fun unquote(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
    private fun baseName(s: String) = s.substringAfterLast('/')

    fun parseFilenameFromHeaders(headers: Map<String, String>, fallbackUrl: String = ""): String {
        val cd = headers.entries.firstOrNull { it.key.equals("Content-Disposition", true) }?.value.orEmpty()
        if (cd.isNotEmpty()) {
            Regex("""filename\*\s*=\s*(?:UTF-8''|utf-8'')([^;\s]+)""", I).find(cd)?.let {
                val n = unquote(it.groupValues[1].trim('"', '\'')); if (n.isNotEmpty()) return baseName(n)
            }
            Regex("""filename\s*=\s*["']?([^;"']+)["']?""", I).find(cd)?.let {
                val n = unquote(it.groupValues[1].trim()); if (n.isNotEmpty()) return baseName(n)
            }
        }
        if (fallbackUrl.isNotEmpty()) {
            val u = runCatching { URI(fallbackUrl) }.getOrNull()
            val query = u?.rawQuery.orEmpty().split('&').filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            for (k in listOf("filename", "file_name", "name", "file")) {
                val v = query[k]?.let(::unquote)?.trim().orEmpty()
                if (v.isNotEmpty()) return baseName(v)
            }
            val name = baseName(u?.rawPath.orEmpty())
            if (name.isNotEmpty() && name !in setOf("file", "download", "uc", "view", "index.html", "index.php")) return unquote(name)
        }
        return ""
    }

    private val CAPTCHA_HINTS = mapOf(
        "1fichier.com" to "1fichier requires solving a free wait timer or using a direct link. Click Download once in your browser, then copy the download link from your browser's Downloads tab and paste it here.",
        "buzzheavier.com" to "BuzzHeavier's landing page is protected by Cloudflare. Click Download once in your browser, then copy the download link from the Downloads tab and paste it here.",
        "bzzhr.to" to "BuzzHeavier's landing page is protected by Cloudflare. Click Download once in your browser, then copy the download link from the Downloads tab and paste it here.",
        "mega.nz" to "MEGA uses client-side decryption, which needs a browser or the MEGA API."
    )

    fun captchaHint(url: String): String? {
        val d = domainOf(url)
        return CAPTCHA_HINTS.entries.firstOrNull { d.contains(it.key) }?.value
    }

    /** Browser-like defaults (as the original sends) plus a host-appropriate Referer. */
    fun requestHeaders(url: String, base: Map<String, String> = emptyMap()): Map<String, String> {
        val h = linkedMapOf(
            "User-Agent" to BROWSER_UA, "Accept" to "*/*", "Accept-Language" to "en-US,en;q=0.9", "Accept-Encoding" to "identity"
        )
        h.putAll(base)
        val u = runCatching { URI(url) }.getOrNull() ?: return h
        val d = u.host?.lowercase().orEmpty()
        when {
            "mediafire.com" in d -> h["Referer"] = "https://www.mediafire.com/"
            "1fichier.com" in d -> h["Referer"] = "https://1fichier.com/"
            !h.keys.any { it.equals("Referer", true) } && u.scheme != null && d.isNotEmpty() -> h["Referer"] = "${u.scheme}://${u.authority}/"
        }
        return h
    }

    /** Hosts that limit free users to one connection or issue single-use links: use one stream there. */
    fun isSingleConnectionHost(url: String): Boolean {
        val d = domainOf(url)
        return listOf("1fichier.com").any { d.contains(it) }
    }

    // ------------------------------------------------------------------ multipart naming

    data class PartInfo(val base: String, val number: Int)
    data class PartItem(val source: String, val name: String? = null)
    data class PartSequence(val isMultipart: Boolean, val mergedName: String, val items: List<PartItem>)

    fun parseMultipartInfo(filename: String): PartInfo? {
        if (filename.isBlank()) return null
        val name = baseName(filename)
        Regex("""^(.*?\.(?:pkg|ffpfsc|exfat|ufs|iso|bin|img|zip|rar|7z|tar|[a-z0-9]{2,6}))\b\.(\d{1,4})$""", I).find(name)?.let { return PartInfo(it.groupValues[1], it.groupValues[2].toInt()) }
        Regex("""^(.*?)[._-]part(\d{1,4})\.([a-z0-9]{2,6})$""", I).find(name)?.let { return PartInfo("${it.groupValues[1]}.${it.groupValues[3]}", it.groupValues[2].toInt()) }
        Regex("""^(.*?\.[a-z0-9]{2,6})[._-]part(\d{1,4})$""", I).find(name)?.let { return PartInfo(it.groupValues[1], it.groupValues[2].toInt()) }
        Regex("""^(.*?)\.(\d{2,4})$""", I).find(name)?.let { return PartInfo(it.groupValues[1], it.groupValues[2].toInt()) }
        Regex("""^(.*?)[_.](\d{1,3})\.([a-z0-9]{2,6})$""", I).find(name)?.let { return PartInfo("${it.groupValues[1]}.${it.groupValues[3]}", it.groupValues[2].toInt()) }
        return null
    }

    /** Detection only. Stitching several sources into one stream is not implemented in the Android port. */
    fun detectMultipartSequence(items: List<PartItem>): PartSequence {
        if (items.size <= 1) return PartSequence(false, "", items)
        val parsed = mutableListOf<Triple<Int, PartItem, String>>()
        val bases = mutableSetOf<String>()
        for (item in items) {
            val raw = item.name?.takeIf { it.isNotEmpty() }
                ?: unquote(baseName(runCatching { URI(item.source).rawPath }.getOrNull().orEmpty()))
            val info = parseMultipartInfo(raw) ?: return PartSequence(false, "", items)
            bases += info.base.lowercase()
            parsed += Triple(info.number, item, info.base)
        }
        if (bases.size != 1) return PartSequence(false, "", items)
        parsed.sortBy { it.first }
        return PartSequence(true, parsed.first().third, parsed.map { it.second })
    }

    /** Only an explicit `password=xyz` / `pass: xyz` in a name is honoured; nothing is guessed from site names. */
    fun explicitPasswordHint(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return Regex("""(?:password|pass)[=:\s_-]+([a-zA-Z0-9_-]+)""", I).find(unquote(text))?.groupValues?.get(1)
    }
}
