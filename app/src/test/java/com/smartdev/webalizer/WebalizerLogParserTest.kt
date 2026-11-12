package com.smartdev.webalizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebalizerLogParserTest {

    @Test
    fun parsesStandardAndVirtualHostLinesWithoutProtocolInTarget() {
        val stats = parse(
            listOf(
                line(target = "/wp-login.php"),
                line(target = "/", host = "example.com", timestamp = "11/Nov/2026:11:00:00 +0100")
            )
        )

        assertEquals(2, stats.parsedLines)
        assertEquals("example.com", stats.site)
        assertTrue(stats.topUrls.any { (url, _) -> url == "/" })
        assertTrue(stats.topUrls.none { (url, _) -> "HTTP/" in url })
    }

    @Test
    fun startsNewVisitAfterMoreThanThirtyMinutes() {
        val stats = parse(
            listOf(
                line(timestamp = "11/Nov/2026:10:00:00 +0100"),
                line(timestamp = "11/Nov/2026:10:20:00 +0100"),
                line(timestamp = "11/Nov/2026:10:51:00 +0100")
            )
        )

        assertEquals(2, stats.totalVisits)
    }

    @Test
    fun detectsProbeScannerAndRepeatedClientErrors() {
        val lines = mutableListOf(
            line(target = "/.env", userAgent = "sqlmap/1.8", status = 404)
        )
        repeat(20) { index ->
            lines += line(
                address = "198.51.100.8",
                target = "/missing-$index",
                status = 404
            )
        }

        val stats = parse(lines)

        assertEquals(1, stats.suspiciousRequestCount)
        assertTrue(stats.suspiciousEvents.first().reasons.contains(SuspicionReason.SENSITIVE_PATH))
        assertTrue(stats.suspiciousEvents.first().reasons.contains(SuspicionReason.SCANNER_USER_AGENT))
        assertTrue(
            stats.suspiciousAddresses.any { address ->
                address.address == "198.51.100.8" &&
                    SuspicionReason.ERROR_BURST in address.reasons
            }
        )
    }

    private fun parse(lines: List<String>): LogStats {
        val file = File.createTempFile("webalizer-test", ".log")
        return try {
            file.writeText(lines.joinToString("\n"))
            WebalizerLogParser().parse(file)
        } finally {
            file.delete()
        }
    }

    private fun line(
        address: String = "192.0.2.10",
        timestamp: String = "11/Nov/2026:10:00:00 +0100",
        target: String = "/index.html",
        status: Int = 200,
        userAgent: String = "Mozilla/5.0",
        host: String? = null
    ): String {
        val prefix = host?.let { "$it " }.orEmpty()
        return "$prefix$address - - [$timestamp] \"GET $target HTTP/1.1\" " +
            "$status 512 \"-\" \"$userAgent\""
    }
}
