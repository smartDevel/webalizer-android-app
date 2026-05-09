package com.smartdev.webalizer

import java.io.File

private val LOG_PATTERN = Regex(
    """^(\S+)\s+\S+\s+\S+\s+\[[^\]]+\]\s+\"(\S+)\s+([^\"]+)\s*[^\"]*\"\s+(\d{3})\s+(\S+).*"""
)

private val ASSET_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "svg", "webp", "ico", "css", "js", "woff", "woff2", "ttf"
)

data class LogStats(
    val totalLines: Int = 0,
    val parsedLines: Int = 0,
    val totalVisitors: Int = 0,
    val totalPages: Int = 0,
    val totalHits: Int = 0,
    val totalBandwidth: Long = 0,
    val topUrls: List<Pair<String, Int>> = emptyList()
)

class WebalizerLogParser {
    fun parse(file: File): LogStats {
        var totalLines = 0
        var parsedLines = 0
        var totalHits = 0
        var totalPages = 0
        var totalBandwidth = 0L

        val uniqueVisitors = mutableSetOf<String>()
        val urlCounter = mutableMapOf<String, Int>()

        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                totalLines++

                val match = LOG_PATTERN.find(line) ?: return@forEach
                parsedLines++

                val ip = match.groupValues[1]
                val method = match.groupValues[2]
                val url = match.groupValues[3]
                val status = match.groupValues[4].toIntOrNull() ?: 0
                val bytesRaw = match.groupValues[5]

                uniqueVisitors += ip
                totalHits++

                val bytes = bytesRaw.toLongOrNull() ?: 0L
                totalBandwidth += bytes

                if (isPageRequest(method, url, status)) {
                    totalPages++
                    urlCounter[url] = (urlCounter[url] ?: 0) + 1
                }
            }
        }

        val topUrls = urlCounter.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value }

        return LogStats(
            totalLines = totalLines,
            parsedLines = parsedLines,
            totalVisitors = uniqueVisitors.size,
            totalPages = totalPages,
            totalHits = totalHits,
            totalBandwidth = totalBandwidth,
            topUrls = topUrls
        )
    }

    private fun isPageRequest(method: String, url: String, status: Int): Boolean {
        if (method !in setOf("GET", "POST", "HEAD")) return false
        if (status !in 200..399) return false

        val path = url.substringBefore('?').lowercase()
        val ext = path.substringAfterLast('.', "")
        if (ext.isNotEmpty() && ext in ASSET_EXTENSIONS) return false

        return true
    }
}
