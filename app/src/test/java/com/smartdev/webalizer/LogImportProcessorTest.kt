package com.smartdev.webalizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipOutputStream

class LogImportProcessorTest {

    @Test
    fun preparesSeveralPlainLogFiles() {
        val output = Files.createTempDirectory("webalizer-import").toFile()
        try {
            val prepared = LogImportProcessor.prepare(
                listOf(
                    source("access.log", "first"),
                    source("access.log.1", "second")
                ),
                output
            )

            assertEquals(2, prepared.files.size)
            assertEquals(0, prepared.archiveCount)
            assertEquals(listOf("first", "second"), prepared.files.map { it.readText() })
        } finally {
            output.deleteRecursively()
        }
    }

    @Test
    fun extractsSeveralZipEntriesWithoutUsingEntryPaths() {
        val output = Files.createTempDirectory("webalizer-zip").toFile()
        val escapedFile = output.parentFile.resolve("escape.log")
        escapedFile.delete()
        try {
            val archive = zip(
                "logs/access.log" to "first",
                "../../escape.log" to "second",
                "__MACOSX/.DS_Store" to "ignored"
            )
            val prepared = LogImportProcessor.prepare(
                listOf(ImportSource("logs.zip") { ByteArrayInputStream(archive) }),
                output
            )

            assertEquals(2, prepared.files.size)
            assertEquals(1, prepared.archiveCount)
            assertTrue(prepared.files.all { it.parentFile == output })
            assertFalse(escapedFile.exists())
            assertEquals(listOf("first", "second"), prepared.files.map { it.readText() })
        } finally {
            output.deleteRecursively()
            escapedFile.delete()
        }
    }

    @Test
    fun decompressesGzipLogAndMakesItParsable() {
        val output = Files.createTempDirectory("webalizer-gzip").toFile()
        val logLine = "192.0.2.10 - - [11/Nov/2026:10:00:00 +0100] " +
            "\"GET /index.html HTTP/1.1\" 200 512 \"-\" \"Mozilla/5.0\""
        try {
            val prepared = LogImportProcessor.prepare(
                listOf(ImportSource("access.log.gz") {
                    ByteArrayInputStream(gzip(logLine))
                }),
                output
            )

            assertEquals(1, prepared.files.size)
            assertEquals(1, prepared.archiveCount)
            assertEquals(logLine, prepared.files.single().readText())
            assertEquals(1, WebalizerLogParser().parse(prepared.files).parsedLines)
        } finally {
            output.deleteRecursively()
        }
    }

    private fun source(name: String, content: String): ImportSource {
        return ImportSource(name) { ByteArrayInputStream(content.toByteArray()) }
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun gzip(content: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { gzip ->
            gzip.write(content.toByteArray())
        }
        return bytes.toByteArray()
    }
}
