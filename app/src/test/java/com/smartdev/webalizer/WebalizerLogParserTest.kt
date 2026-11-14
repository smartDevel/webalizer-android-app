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
    fun aggregatesFilesAndKeepsVisitsAcrossFileBoundaries() {
        val first = createLogFile(
            listOf(line(timestamp = "11/Nov/2026:10:00:00 +0100"))
        )
        val second = createLogFile(
            listOf(
                line(timestamp = "11/Nov/2026:10:20:00 +0100"),
                line(
                    address = "192.0.2.20",
                    timestamp = "12/Nov/2026:08:00:00 +0100"
                )
            )
        )

        try {
            val stats = WebalizerLogParser().parse(listOf(first, second))

            assertEquals(3, stats.totalHits)
            assertEquals(2, stats.totalVisitors)
            assertEquals(2, stats.totalVisits)
            assertEquals(2, stats.activeDays)
        } finally {
            first.delete()
            second.delete()
        }
    }

    @Test
    fun aggregatesSuspiciousAddressAcrossFilesWithObservedPeriod() {
        val first = createLogFile(
            listOf(
                line(
                    address = "203.0.113.50",
                    target = "/.env",
                    timestamp = "10/Nov/2026:08:00:00 +0100",
                    status = 404
                )
            )
        )
        val second = createLogFile(
            listOf(
                line(
                    address = "203.0.113.50",
                    target = "/.git/config",
                    timestamp = "12/Nov/2026:18:00:00 +0100",
                    status = 404
                )
            )
        )

        try {
            val address = WebalizerLogParser()
                .parse(listOf(first, second))
                .suspiciousAddresses
                .single()

            assertEquals(2, address.suspiciousRequests)
            assertEquals(2, address.sourceFileCount)
            assertTrue(address.firstTimestamp != null)
            assertTrue(address.lastTimestamp != null)
            assertTrue(address.firstTimestamp!! < address.lastTimestamp!!)
        } finally {
            first.delete()
            second.delete()
        }
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

    @Test
    fun aggregatesDailyHitsFilesPagesAndSuspiciousRequests() {
        val stats = parse(
            listOf(
                line(target = "/index.html", status = 200),
                line(target = "/style.css", status = 200),
                line(target = "/missing", status = 404),
                line(target = "/.env", status = 404)
            )
        )

        val day = stats.dailyTraffic.single()
        assertEquals(4, day.hits)
        assertEquals(2, day.files)
        assertEquals(1, day.pages)
        assertEquals(1, day.visits)
        assertEquals(1, day.suspicious)
    }

    @Test
    fun assignsVisitStartsToTheirRespectiveDays() {
        val stats = parse(
            listOf(
                line(timestamp = "10/Nov/2026:10:00:00 +0100"),
                line(timestamp = "10/Nov/2026:10:20:00 +0100"),
                line(timestamp = "11/Nov/2026:12:00:00 +0100")
            )
        )

        assertEquals(listOf(1, 1), stats.dailyTraffic.map { it.visits })
        assertEquals(2, stats.totalVisits)
    }

    @Test
    fun loadsSuspiciousEventsForSelectedDayAcrossFilesAndKeepsTotal() {
        val first = createLogFile(
            listOf(
                line(target = "/.env", timestamp = "11/Nov/2026:08:00:00 +0100"),
                line(target = "/safe", timestamp = "11/Nov/2026:09:00:00 +0100")
            )
        )
        val second = createLogFile(
            listOf(
                line(target = "/.git/config", timestamp = "11/Nov/2026:10:00:00 +0100"),
                line(target = "/.env", timestamp = "12/Nov/2026:08:00:00 +0100")
            )
        )

        try {
            val details = WebalizerLogParser().findSuspiciousEvents(
                files = listOf(first, second),
                day = "2026-11-11",
                maxEvents = 1
            )

            assertEquals(2, details.totalCount)
            assertEquals(1, details.events.size)
            assertEquals("/.env", details.events.single().target)
        } finally {
            first.delete()
            second.delete()
        }
    }

    private fun parse(lines: List<String>): LogStats {
        val file = createLogFile(lines)
        return try {
            WebalizerLogParser().parse(file)
        } finally {
            file.delete()
        }
    }

    private fun createLogFile(lines: List<String>): File {
        return File.createTempFile("webalizer-test", ".log").apply {
            writeText(lines.joinToString("\n"))
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
