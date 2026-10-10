package com.directstream.ps5.core

import com.directstream.ps5.core.diag.ErrorReport
import kotlin.test.*
import org.junit.Test

class ReportTests {
    private val input = ErrorReport.Input("1.0", "14", "Pixel", "cdn.example.com", "SOURCE_CHANGED",
        "failed https://cdn.example.com/f?token=SECRET123 for user", "TRANSFERRING", 10, 100, "PARALLEL_ORDERED", true, true, "220 ftpd at 192.168.1.5")
    @Test fun receiptContainsNoSecrets() {
        val r = ErrorReport.build(input)
        assertFalse("SECRET123" in r); assertTrue("SOURCE_CHANGED" in r); assertTrue("cdn.example.com" in r)
    }
    @Test fun issueLinkIsEncodedAndBounded() {
        val url = ErrorReport.githubIssueUrl("Transfer failed", ErrorReport.build(input) + "x".repeat(10_000))
        assertTrue(url.startsWith("https://github.com/ChillQuant/direct-stream-ps5/issues/new?"))
        assertFalse(" " in url); assertFalse("\n" in url); assertTrue(url.length < 9000)
    }
}
