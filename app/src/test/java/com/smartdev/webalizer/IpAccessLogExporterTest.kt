package com.smartdev.webalizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class IpAccessLogExporterTest {

    @Test
    fun exportsAllAndOnlyRawLinesForExactAddressAcrossFiles() {
        val directory = Files.createTempDirectory("webalizer-ip-log").toFile()
        val firstMatch = line("192.0.2.10", "/first")
        val secondMatch = line("192.0.2.10", "/second", "example.com ")
        val otherAddress = line("192.0.2.100", "/other")
        val firstFile = directory.resolve("first.log").apply {
            writeText(listOf(firstMatch, otherAddress).joinToString("\n"))
        }
        val secondFile = directory.resolve("second.log").apply {
            writeText(secondMatch)
        }

        try {
            val export = IpAccessLogExporter.export(
                listOf(firstFile, secondFile),
                "192.0.2.10",
                directory.resolve("exports")
            )

            assertEquals(2, export.totalLines)
            assertTrue(export.file.readText().contains(firstMatch))
            assertTrue(export.file.readText().contains(secondMatch))
            assertFalse(export.file.readText().contains(otherAddress))
            assertEquals(export.file.readText().trim(), export.preview)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun line(address: String, target: String, host: String = ""): String {
        return "$host$address - - [11/Nov/2026:10:00:00 +0100] " +
            "\"GET $target HTTP/1.1\" 200 512 \"-\" \"Mozilla/5.0\""
    }
}
