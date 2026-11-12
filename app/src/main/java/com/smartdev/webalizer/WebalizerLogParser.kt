package com.smartdev.webalizer

import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale

private val LOG_PATTERN = Regex(
    """^(?:(\S+)\s+)?(\S+)\s+\S+\s+\S+\s+\[([^\]]+)\]\s+\"(\S+)\s+(\S+)(?:\s+[^\"]*)?\"\s+(\d{3})\s+(\S+)(?:\s+\"([^\"]*)\"\s+\"([^\"]*)\")?.*"""
)

private const val VISIT_TIMEOUT_MILLIS = 30 * 60 * 1000L
private const val MAX_SUSPICIOUS_EVENTS = 50
private const val ERROR_BURST_MINIMUM = 20
private const val ERROR_BURST_RATIO = 0.70

private val SENSITIVE_PATH_INDICATORS = listOf(
    "/.env", "/.git", "/wp-config", "/wp-login.php", "/wp-admin", "/xmlrpc.php",
    "/phpmyadmin", "/administrator", "/vendor/phpunit", "/cgi-bin/", "/server-status"
)

private val ATTACK_PAYLOAD_INDICATORS = listOf(
    "../", "%2e%2e", "%252e", "/etc/passwd", "/proc/self", "<script", "%3cscript",
    "union%20select", "union+select", "sleep(", "cmd=", "exec=", "/bin/sh"
)

private val SCANNER_USER_AGENT_INDICATORS = listOf(
    "sqlmap", "nikto", "nmap", "masscan", "zgrab", "nuclei", "gobuster", "dirbuster",
    "acunetix", "nessus", "wpscan"
)

private val UNUSUAL_METHODS = setOf("TRACE", "TRACK", "CONNECT", "DEBUG")

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
    val suspiciousRequestCount: Int = 0,
    val suspiciousAddressCount: Int = 0,
    val suspiciousAddresses: List<SuspiciousAddress> = emptyList(),
    val suspiciousEvents: List<SuspiciousEvent> = emptyList(),
    val topUrls: List<Pair<String, Int>> = emptyList()
)

enum class SuspicionReason {
    SENSITIVE_PATH,
    ATTACK_PAYLOAD,
    SCANNER_USER_AGENT,
    UNUSUAL_METHOD,
    ERROR_BURST
}

data class SuspiciousAddress(
    val address: String,
    val totalHits: Int,
    val clientErrors: Int,
    val suspiciousRequests: Int,
    val reasons: Set<SuspicionReason>
)

data class SuspiciousEvent(
    val timestamp: Long?,
    val address: String,
    val method: String,
    val target: String,
    val status: Int,
    val reasons: Set<SuspicionReason>
)

private data class AddressRisk(
    var totalHits: Int = 0,
    var clientErrors: Int = 0,
    var suspiciousRequests: Int = 0,
    val reasons: MutableSet<SuspicionReason> = mutableSetOf()
)

class WebalizerLogParser {
    fun parse(file: File): LogStats = parse(listOf(file))

    fun parse(files: Iterable<File>): LogStats {
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
        val riskByAddress = mutableMapOf<String, AddressRisk>()
        val suspiciousEvents = mutableListOf<SuspiciousEvent>()
        val timestampParser = SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z", Locale.US).apply {
            isLenient = false
        }
        val dayFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val hourFormatter = SimpleDateFormat("yyyy-MM-dd HH:00", Locale.ROOT)
        var firstTimestamp: Long? = null
        var lastTimestamp: Long? = null
        var suspiciousRequestCount = 0

        files.forEach { file ->
            file.bufferedReader().useLines { lines ->
                lines.forEach lineLoop@{ line ->
                    totalLines++

                    val match = LOG_PATTERN.find(line) ?: return@lineLoop
                    parsedLines++

                    val virtualHost = match.groupValues[1].takeIf { it.isNotBlank() && it != "-" }
                    val ip = match.groupValues[2]
                    val timestampRaw = match.groupValues[3]
                    val method = match.groupValues[4]
                    val url = match.groupValues[5]
                    val status = match.groupValues[6].toIntOrNull() ?: 0
                    val bytesRaw = match.groupValues[7]
                    val userAgent = match.groupValues[9]

                    uniqueVisitors += ip
                    addressCounter[ip] = (addressCounter[ip] ?: 0) + 1
                    totalHits++

                    val addressRisk = riskByAddress.getOrPut(ip) { AddressRisk() }
                    addressRisk.totalHits++
                    if (status in 400..499) {
                        addressRisk.clientErrors++
                    }

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

                    val suspicionReasons = detectSuspicion(method, url, userAgent)
                    if (suspicionReasons.isNotEmpty()) {
                        suspiciousRequestCount++
                        addressRisk.suspiciousRequests++
                        addressRisk.reasons += suspicionReasons
                        suspiciousEvents +=
                            SuspiciousEvent(
                                timestamp = timestamp?.time,
                                address = ip,
                                method = method,
                                target = url,
                                status = status,
                                reasons = suspicionReasons
                            )
                        if (suspiciousEvents.size > MAX_SUSPICIOUS_EVENTS) {
                            suspiciousEvents.removeAt(
                                suspiciousEvents.indices.minByOrNull { index ->
                                    suspiciousEvents[index].timestamp ?: Long.MIN_VALUE
                                } ?: 0
                            )
                        }
                    }

                    val bytes = bytesRaw.toLongOrNull() ?: 0L
                    totalBandwidth += bytes

                    if (isPageRequest(method, url, status)) {
                        totalPages++
                        urlCounter[url] = (urlCounter[url] ?: 0) + 1
                    }
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

        val allSuspiciousAddresses = riskByAddress.mapNotNull { (address, risk) ->
            val errorRatio = if (risk.totalHits == 0) {
                0.0
            } else {
                risk.clientErrors.toDouble() / risk.totalHits
            }
            val hasErrorBurst = risk.clientErrors >= ERROR_BURST_MINIMUM &&
                errorRatio >= ERROR_BURST_RATIO
            if (risk.suspiciousRequests == 0 && !hasErrorBurst) {
                return@mapNotNull null
            }

            val reasons = risk.reasons.toMutableSet()
            if (hasErrorBurst) reasons += SuspicionReason.ERROR_BURST
            SuspiciousAddress(
                address = address,
                totalHits = risk.totalHits,
                clientErrors = risk.clientErrors,
                suspiciousRequests = risk.suspiciousRequests,
                reasons = reasons
            )
        }.sortedWith(
            compareByDescending<SuspiciousAddress> { it.suspiciousRequests }
                .thenByDescending { it.clientErrors }
        )
        val suspiciousAddresses = allSuspiciousAddresses.take(10)

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
            suspiciousRequestCount = suspiciousRequestCount,
            suspiciousAddressCount = allSuspiciousAddresses.size,
            suspiciousAddresses = suspiciousAddresses,
            suspiciousEvents = suspiciousEvents.sortedByDescending { it.timestamp ?: Long.MIN_VALUE },
            topUrls = topUrls
        )
    }

    private fun detectSuspicion(
        method: String,
        target: String,
        userAgent: String
    ): Set<SuspicionReason> {
        val reasons = mutableSetOf<SuspicionReason>()
        val normalizedTarget = target.lowercase(Locale.ROOT)
        val normalizedUserAgent = userAgent.lowercase(Locale.ROOT)

        if (SENSITIVE_PATH_INDICATORS.any(normalizedTarget::contains)) {
            reasons += SuspicionReason.SENSITIVE_PATH
        }
        if (ATTACK_PAYLOAD_INDICATORS.any(normalizedTarget::contains)) {
            reasons += SuspicionReason.ATTACK_PAYLOAD
        }
        if (SCANNER_USER_AGENT_INDICATORS.any(normalizedUserAgent::contains)) {
            reasons += SuspicionReason.SCANNER_USER_AGENT
        }
        if (method.uppercase(Locale.ROOT) in UNUSUAL_METHODS) {
            reasons += SuspicionReason.UNUSUAL_METHOD
        }
        return reasons
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
