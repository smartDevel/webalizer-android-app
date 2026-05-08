package com.smartdev.webalizer

import java.io.File
import java.util.regex.Pattern

data class LogStats(
    val totalLines: Int = 0,
    val totalVisitors: Int = 0,
    val totalPages: Int = 0,
    val totalBandwidth: Long = 0
)

class WebalizerLogParser {
    fun parse(file: File): LogStats {
        var lines = 0
        var visitors = 0
        var pages = 0
        var bandwidth = 0L

        file.bufferedReader().use { reader ->
            reader.forEachLine { line ->
                lines++
                // Webalizer-Format: IP - - [date] "GET /page.html" 200 1234
                // IP - - [timestamp] "REQUEST" status bytes
                if (line.contains("-") && line.contains("[")) {
                    pages++
                    // Extract bytes
                    val bytesPattern = Pattern.compile("\d+\s*-$")
                    val matcher = bytesPattern.matcher(line)
                    if (matcher.find()) {
                        try {
                            bandwidth += matcher.group().trim().toLong()
                        } catch (e: Exception) {
                            // ignore parse error
                        }
                    }
                }
            }
        }

        return LogStats(
            totalLines = lines,
            totalVisitors = visitors,
            totalPages = pages,
            totalBandwidth = bandwidth
        )
    }
}
