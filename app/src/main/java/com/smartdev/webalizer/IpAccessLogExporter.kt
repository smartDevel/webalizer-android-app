package com.smartdev.webalizer

import java.io.File

data class IpAccessLogExport(
    val file: File,
    val totalLines: Int,
    val preview: String,
    val previewTruncated: Boolean
)

object IpAccessLogExporter {
    const val MAX_PREVIEW_CHARS = 200_000

    fun export(
        files: Iterable<File>,
        address: String,
        outputDirectory: File
    ): IpAccessLogExport {
        check(outputDirectory.mkdirs() || outputDirectory.isDirectory) {
            "Temporärer Exportordner konnte nicht angelegt werden"
        }

        val safeAddress = address.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val output = File(outputDirectory, "zugriffe-$safeAddress.log")
        val preview = StringBuilder()
        var totalLines = 0
        var previewTruncated = false

        output.bufferedWriter().use { writer ->
            files.forEach { file ->
                file.bufferedReader().useLines { lines ->
                    lines.forEach lineLoop@{ line ->
                        if (extractLogAddress(line) != address) return@lineLoop

                        writer.appendLine(line)
                        totalLines++
                        if (!previewTruncated) {
                            val requiredChars = line.length + 1
                            if (preview.length + requiredChars <= MAX_PREVIEW_CHARS) {
                                preview.appendLine(line)
                            } else {
                                previewTruncated = true
                            }
                        }
                    }
                }
            }
        }

        return IpAccessLogExport(
            file = output,
            totalLines = totalLines,
            preview = preview.toString().trimEnd(),
            previewTruncated = previewTruncated
        )
    }
}
