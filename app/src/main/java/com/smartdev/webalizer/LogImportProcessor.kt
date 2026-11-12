package com.smartdev.webalizer

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class ImportSource(
    val name: String,
    val openStream: () -> InputStream
)

data class PreparedLogs(
    val files: List<File>,
    val sourceNames: List<String>,
    val archiveCount: Int
)

object LogImportProcessor {
    const val MAX_SELECTED_SOURCES = 20
    const val MAX_ARCHIVE_ENTRIES = 100
    const val MAX_FILE_BYTES = 100L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 250L * 1024 * 1024

    fun prepare(sources: List<ImportSource>, outputDirectory: File): PreparedLogs {
        require(sources.isNotEmpty()) { "Keine Dateien ausgewählt" }
        require(sources.size <= MAX_SELECTED_SOURCES) {
            "Höchstens $MAX_SELECTED_SOURCES Dateien gleichzeitig auswählen"
        }

        check(!outputDirectory.exists() || outputDirectory.deleteRecursively()) {
            "Vorherige temporäre Importdaten konnten nicht entfernt werden"
        }
        check(outputDirectory.mkdirs() || outputDirectory.isDirectory) {
            "Temporärer Importordner konnte nicht angelegt werden"
        }

        val files = mutableListOf<File>()
        var totalBytes = 0L
        var archiveCount = 0

        sources.forEachIndexed { sourceIndex, source ->
            val input = BufferedInputStream(source.openStream())
            input.use { buffered ->
                if (isZip(buffered)) {
                    archiveCount++
                    totalBytes = extractZip(
                        input = buffered,
                        outputDirectory = outputDirectory,
                        sourceIndex = sourceIndex,
                        files = files,
                        initialTotalBytes = totalBytes
                    )
                } else {
                    val output = File(outputDirectory, "source-$sourceIndex.log")
                    FileOutputStream(output).use { target ->
                        totalBytes = copyLimited(buffered, target, totalBytes)
                    }
                    files += output
                }
            }
        }

        check(files.isNotEmpty()) { "Keine Logdateien gefunden" }
        return PreparedLogs(
            files = files,
            sourceNames = sources.map { it.name },
            archiveCount = archiveCount
        )
    }

    private fun isZip(input: BufferedInputStream): Boolean {
        input.mark(4)
        val signature = ByteArray(4)
        val count = input.read(signature)
        input.reset()
        if (count < 4) return false

        return signature[0] == 0x50.toByte() && signature[1] == 0x4b.toByte() &&
            ((signature[2] == 0x03.toByte() && signature[3] == 0x04.toByte()) ||
                (signature[2] == 0x05.toByte() && signature[3] == 0x06.toByte()) ||
                (signature[2] == 0x07.toByte() && signature[3] == 0x08.toByte()))
    }

    private fun extractZip(
        input: InputStream,
        outputDirectory: File,
        sourceIndex: Int,
        files: MutableList<File>,
        initialTotalBytes: Long
    ): Long {
        var totalBytes = initialTotalBytes
        var inspectedEntries = 0
        var extractedEntries = 0

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                inspectedEntries++
                check(inspectedEntries <= MAX_ARCHIVE_ENTRIES) {
                    "ZIP enthält mehr als $MAX_ARCHIVE_ENTRIES Einträge"
                }

                if (!entry.isDirectory && isPotentialLogEntry(entry.name)) {
                    check(entry.size < 0 || entry.size <= MAX_FILE_BYTES) {
                        "ZIP-Eintrag ist größer als ${MAX_FILE_BYTES / 1024 / 1024} MB"
                    }
                    val output = File(
                        outputDirectory,
                        "archive-$sourceIndex-entry-$extractedEntries.log"
                    )
                    FileOutputStream(output).use { target ->
                        totalBytes = copyLimited(zip, target, totalBytes)
                    }
                    files += output
                    extractedEntries++
                } else if (!entry.isDirectory) {
                    totalBytes = consumeLimited(zip, totalBytes)
                }
                zip.closeEntry()
            }
        }

        check(extractedEntries > 0) { "ZIP enthält keine unterstützten Logdateien" }
        return totalBytes
    }

    private fun isPotentialLogEntry(name: String): Boolean {
        val normalized = name.replace('\\', '/').lowercase()
        val fileName = normalized.substringAfterLast('/')
        if (normalized.startsWith("__macosx/") || fileName.isBlank() || fileName == ".ds_store") {
            return false
        }
        return !fileName.endsWith(".zip")
    }

    private fun copyLimited(input: InputStream, output: FileOutputStream, initialTotalBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var fileBytes = 0L
        var totalBytes = initialTotalBytes
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            fileBytes += count
            totalBytes += count
            check(fileBytes <= MAX_FILE_BYTES) {
                "Eine Logdatei ist größer als ${MAX_FILE_BYTES / 1024 / 1024} MB"
            }
            check(totalBytes <= MAX_TOTAL_BYTES) {
                "Die ausgewählten Logdaten sind größer als ${MAX_TOTAL_BYTES / 1024 / 1024} MB"
            }
            output.write(buffer, 0, count)
        }
        return totalBytes
    }

    private fun consumeLimited(input: InputStream, initialTotalBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var fileBytes = 0L
        var totalBytes = initialTotalBytes
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            fileBytes += count
            totalBytes += count
            check(fileBytes <= MAX_FILE_BYTES && totalBytes <= MAX_TOTAL_BYTES) {
                "ZIP enthält zu große, nicht unterstützte Einträge"
            }
        }
        return totalBytes
    }
}
