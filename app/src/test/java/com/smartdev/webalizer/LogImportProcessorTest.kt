package com.smartdev.webalizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
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
}
