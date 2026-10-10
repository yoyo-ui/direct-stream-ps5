package com.directstream.ps5.core.util

/** Removes secrets from anything that might reach a log, notification or error report. */
object Redaction {
    private val SENSITIVE_QUERY_KEYS = setOf(
        "token", "access_token", "refresh_token", "id_token", "key", "apikey", "api_key", "password",
        "passwd", "pass", "pwd", "secret", "sig", "signature", "auth", "authorization", "session",
        "sessionid", "sid", "code", "x-amz-signature", "x-amz-credential", "x-amz-security-token",
        "expires", "policy", "hmac", "jwt"
    )
    private val SENSITIVE_HEADERS = setOf(
        "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token"
    )
    private val URL_IN_TEXT = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val USERINFO = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*://)([^/@?#]*@)""")

    fun redactUrl(url: String): String {
        var s = USERINFO.replace(url) { it.groupValues[1] + "***@" }
        val hashIdx = s.indexOf('#')
        val fragment = if (hashIdx >= 0) "#***" else ""
        if (hashIdx >= 0) s = s.substring(0, hashIdx)
        val q = s.indexOf('?')
        if (q < 0) return s + fragment
        val base = s.substring(0, q)
        val query = s.substring(q + 1).split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) pair else {
                val k = pair.substring(0, eq)
                if (k.lowercase() in SENSITIVE_QUERY_KEYS) "$k=***" else pair
            }
        }
        return "$base?$query$fragment"
    }

    fun redactHeader(name: String, value: String): String =
        if (name.lowercase() in SENSITIVE_HEADERS) "***" else value

    fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (k, v) -> redactHeader(k, v) }

    /** "PASS hunter2" -> "PASS ***". Everything else is returned unchanged. */
    fun redactFtpCommand(line: String): String =
        if (line.startsWith("PASS ", ignoreCase = true)) "PASS ***"
        else if (line.startsWith("ACCT ", ignoreCase = true)) "ACCT ***" else line

    fun redactText(text: String): String = URL_IN_TEXT.replace(text) { redactUrl(it.value) }
}
