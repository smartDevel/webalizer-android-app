package com.smartdev.webalizer

import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale

private val LOG_PATTERN = Regex(
    """^(?:(\S+)\s+)?(\S+)\s+\S+\s+\S+\s+\[([^\]]+)\]\s+\"(\S+)\s+(\S+)(?:\s+[^\"]*)?\"\s+(\d{3})\s+(\S+).*"""
)

private const val VISIT_TIMEOUT_MILLIS = 30 * 60 * 1000L

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
    val totalVisits: Int = 0,
    val site: String? = null,
    val firstTimestamp: Long? = null,
    val lastTimestamp: Long? = null,
    val activeDays: Int = 0,
    val activeHours: Int = 0,
    val busiestDay: Pair<String, Int>? = null,
    val busiestHour: Pair<String, Int>? = null,
    val hitsByDay: List<Pair<String, Int>> = emptyList(),
    val topAddresses: List<Pair<String, Int>> = emptyList(),
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
        val addressCounter = mutableMapOf<String, Int>()
        val siteCounter = mutableMapOf<String, Int>()
        val dayCounter = mutableMapOf<String, Int>()
        val hourCounter = mutableMapOf<String, Int>()
        val timestampsByAddress = mutableMapOf<String, MutableList<Long>>()
        val timestampParser = SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z", Locale.US).apply {
            isLenient = false
        }
        val dayFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val hourFormatter = SimpleDateFormat("yyyy-MM-dd HH:00", Locale.ROOT)
        var firstTimestamp: Long? = null
        var lastTimestamp: Long? = null

        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                totalLines++

                val match = LOG_PATTERN.find(line) ?: return@forEach
                parsedLines++

                val virtualHost = match.groupValues[1].takeIf { it.isNotBlank() && it != "-" }
                val ip = match.groupValues[2]
                val timestampRaw = match.groupValues[3]
                val method = match.groupValues[4]
                val url = match.groupValues[5]
                val status = match.groupValues[6].toIntOrNull() ?: 0
                val bytesRaw = match.groupValues[7]

                uniqueVisitors += ip
                addressCounter[ip] = (addressCounter[ip] ?: 0) + 1
                totalHits++

                val site = virtualHost ?: extractHost(url)
                if (site != null) {
                    siteCounter[site] = (siteCounter[site] ?: 0) + 1
                }

                val timestamp = runCatching { timestampParser.parse(timestampRaw) }.getOrNull()
                if (timestamp != null) {
                    val timestampMillis = timestamp.time
                    firstTimestamp = minOf(firstTimestamp ?: timestampMillis, timestampMillis)
                    lastTimestamp = maxOf(lastTimestamp ?: timestampMillis, timestampMillis)
                    timestampsByAddress.getOrPut(ip) { mutableListOf() } += timestampMillis

                    val day = dayFormatter.format(timestamp)
                    val hour = hourFormatter.format(timestamp)
                    dayCounter[day] = (dayCounter[day] ?: 0) + 1
                    hourCounter[hour] = (hourCounter[hour] ?: 0) + 1
                }

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

        val topAddresses = addressCounter.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value }

        val hitsByDay = dayCounter.entries
            .sortedBy { it.key }
            .takeLast(14)
            .map { it.key to it.value }

        val totalVisits = timestampsByAddress.values.sumOf { timestamps ->
            countVisits(timestamps)
        }

        return LogStats(
            totalLines = totalLines,
            parsedLines = parsedLines,
            totalVisitors = uniqueVisitors.size,
            totalPages = totalPages,
            totalHits = totalHits,
            totalBandwidth = totalBandwidth,
            totalVisits = totalVisits,
            site = siteCounter.maxByOrNull { it.value }?.key,
            firstTimestamp = firstTimestamp,
            lastTimestamp = lastTimestamp,
            activeDays = dayCounter.size,
            activeHours = hourCounter.size,
            busiestDay = dayCounter.maxByOrNull { it.value }?.let { it.key to it.value },
            busiestHour = hourCounter.maxByOrNull { it.value }?.let { it.key to it.value },
            hitsByDay = hitsByDay,
            topAddresses = topAddresses,
            topUrls = topUrls
        )
    }

    private fun countVisits(timestamps: MutableList<Long>): Int {
        if (timestamps.isEmpty()) return 0

        timestamps.sort()
        var visits = 1
        for (index in 1 until timestamps.size) {
            if (timestamps[index] - timestamps[index - 1] > VISIT_TIMEOUT_MILLIS) {
                visits++
            }
        }
        return visits
    }

    private fun extractHost(url: String): String? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return runCatching { URI(url).host }.getOrNull()
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
