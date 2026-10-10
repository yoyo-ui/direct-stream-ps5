package com.directstream.ps5.core.diag

import com.directstream.ps5.core.util.Redaction
import java.net.URLEncoder

/** Builds the sanitised receipt shown for a failed transfer, and a pre-filled GitHub issue link. */
object ErrorReport {
    const val ISSUE_URL = "https://github.com/ChillQuant/direct-stream-ps5/issues/new"

    data class Input(
        val appVersion: String, val androidVersion: String, val device: String,
        val sourceHost: String?, val errorKind: String, val message: String,
        val phase: String?, val bytesDone: Long, val totalBytes: Long?, val mode: String?,
        val rangesSupported: Boolean?, val hasValidator: Boolean?, val ftpServerBanner: String?
    )

    fun build(i: Input): String = buildString {
        appendLine("Direct Stream for PS5 (Android) error receipt")
        appendLine("App: ${i.appVersion}   Android: ${i.androidVersion}   Device: ${i.device}")
        appendLine("Error: ${i.errorKind}")
        appendLine("Message: ${Redaction.redactText(i.message)}")
        i.phase?.let { appendLine("Phase: $it") }
        appendLine("Progress: ${i.bytesDone} / ${i.totalBytes ?: "unknown"} bytes   Mode: ${i.mode ?: "n/a"}")
        appendLine("Source host: ${i.sourceHost ?: "n/a"}   Ranges: ${i.rangesSupported ?: "n/a"}   Resume validator: ${i.hasValidator ?: "n/a"}")
        appendLine("FTP server: ${i.ftpServerBanner?.let { Redaction.redactText(it).take(80) } ?: "n/a"}")
        appendLine("(No IP addresses, passwords, URLs with tokens or file names are included.)")
    }

    fun githubIssueUrl(title: String, receipt: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val body = "**What happened**\n\n(describe)\n\n```\n${receipt.take(4000)}\n```\n"
        return "$ISSUE_URL?title=${enc(title.take(120))}&body=${enc(body)}"
    }
}
