package com.smartdev.webalizer

import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LOG_PATTERN = Regex(
    """^(?:(\S+)\s+)?(\S+)\s+\S+\s+\S+\s+\[([^\]]+)\]\s+\"(\S+)\s+(\S+)(?:\s+[^\"]*)?\"\s+(\d{3})\s+(\S+)(?:\s+\"([^\"]*)\"\s+\"([^\"]*)\")?.*"""
)

internal fun extractLogAddress(line: String): String? {
    return LOG_PATTERN.find(line)?.groupValues?.get(2)
}

private const val VISIT_TIMEOUT_MILLIS = 30 * 60 * 1000L
private const val MAX_SUSPICIOUS_EVENTS = 50
private const val MAX_SUSPICIOUS_ADDRESSES = 20
private const val MAX_DAILY_TRAFFIC_DAYS = 180
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
    val dailyTraffic: List<DailyTraffic> = emptyList(),
    val topAddresses: List<Pair<String, Int>> = emptyList(),
    val suspiciousRequestCount: Int = 0,
    val suspiciousAddressCount: Int = 0,
    val suspiciousAddresses: List<SuspiciousAddress> = emptyList(),
    val suspiciousEvents: List<SuspiciousEvent> = emptyList(),
    val topUrls: List<Pair<String, Int>> = emptyList()
)

data class DailyTraffic(
    val day: String,
    val hits: Int,
    val files: Int,
    val pages: Int,
    val visits: Int,
    val suspicious: Int
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
    val sourceFileCount: Int,
    val firstTimestamp: Long?,
    val lastTimestamp: Long?,
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

data class SuspiciousDayDetails(
    val totalCount: Int,
    val events: List<SuspiciousEvent>
)

data class RequestEvent(
    val timestamp: Long,
    val address: String,
    val method: String,
    val target: String,
    val status: Int
)

data class DailyRequestDetails(
    val totalCount: Int,
    val events: List<RequestEvent>
)

data class VisitDetail(
    val address: String,
    val firstTimestamp: Long,
    val lastTimestamp: Long,
    val hits: Int,
    val files: Int,
    val pages: Int,
    val suspicious: Int
)

data class DailyVisitDetails(
    val totalCount: Int,
    val visits: List<VisitDetail>
)

private data class AddressRisk(
    var totalHits: Int = 0,
    var clientErrors: Int = 0,
    var suspiciousRequests: Int = 0,
    var firstTimestamp: Long? = null,
    var lastTimestamp: Long? = null,
    val sourceFiles: MutableSet<Int> = mutableSetOf(),
    val reasons: MutableSet<SuspicionReason> = mutableSetOf()
)

private data class VisitRequest(
    val timestamp: Long,
    val isFile: Boolean,
    val isPage: Boolean,
    val isSuspicious: Boolean
)

class WebalizerLogParser {
    fun parse(file: File): LogStats = parse(listOf(file))

    fun findSuspiciousEvents(
        files: Iterable<File>,
        day: String,
        maxEvents: Int = 500
    ): SuspiciousDayDetails {
        require(maxEvents > 0) { "maxEvents must be positive" }
        val events = mutableListOf<SuspiciousEvent>()
        var totalCount = 0
        val timestampParser = SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z", Locale.US).apply {
            isLenient = false
        }
        val dayFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

        files.forEach { file ->
            file.bufferedReader().useLines { lines ->
                lines.forEach lineLoop@{ line ->
                    val match = LOG_PATTERN.find(line) ?: return@lineLoop
                    val timestamp = runCatching {
                        timestampParser.parse(match.groupValues[3])
                    }.getOrNull() ?: return@lineLoop
                    if (dayFormatter.format(timestamp) != day) return@lineLoop

                    val method = match.groupValues[4]
                    val target = match.groupValues[5]
                    val reasons = detectSuspicion(method, target, match.groupValues[9])
                    if (reasons.isEmpty()) return@lineLoop

                    totalCount++
                    if (events.size < maxEvents) {
                        events += SuspiciousEvent(
                            timestamp = timestamp.time,
                            address = match.groupValues[2],
                            method = method,
                            target = target,
                            status = match.groupValues[6].toIntOrNull() ?: 0,
                            reasons = reasons
                        )
                    }
                }
            }
        }

        return SuspiciousDayDetails(
            totalCount = totalCount,
            events = events.sortedBy { it.timestamp ?: Long.MIN_VALUE }
        )
    }

    fun findFileEvents(
        files: Iterable<File>,
        day: String,
        maxEvents: Int = 500
    ): DailyRequestDetails {
        require(maxEvents > 0) { "maxEvents must be positive" }
        val events = mutableListOf<RequestEvent>()
        var totalCount = 0
        val timestampParser = SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z", Locale.US).apply {
            isLenient = false
        }
        val dayFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

        files.forEach { file ->
            file.bufferedReader().useLines { lines ->
                lines.forEach lineLoop@{ line ->
                    val match = LOG_PATTERN.find(line) ?: return@lineLoop
                    val status = match.groupValues[6].toIntOrNull() ?: return@lineLoop
                    if (!isFileRequest(status)) return@lineLoop
                    val timestamp = runCatching {
                        timestampParser.parse(match.groupValues[3])
                    }.getOrNull() ?: return@lineLoop
                    if (dayFormatter.format(timestamp) != day) return@lineLoop

                    totalCount++
                    if (events.size < maxEvents) {
                        events += RequestEvent(
                            timestamp = timestamp.time,
                            address = match.groupValues[2],
                            method = match.groupValues[4],
                            target = match.groupValues[5],
                            status = status
                        )
                    }
                }
            }
        }

        return DailyRequestDetails(
            totalCount = totalCount,
            events = events.sortedBy(RequestEvent::timestamp)
        )
    }

    fun findVisits(
        files: Iterable<File>,
        day: String,
        maxVisits: Int = 500
    ): DailyVisitDetails {
        require(maxVisits > 0) { "maxVisits must be positive" }
        val requestsByAddress = mutableMapOf<String, MutableList<VisitRequest>>()
        val timestampParser = SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z", Locale.US).apply {
            isLenient = false
        }
        val dayFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

        files.forEach { file ->
            file.bufferedReader().useLines { lines ->
                lines.forEach lineLoop@{ line ->
                    val match = LOG_PATTERN.find(line) ?: return@lineLoop
                    val timestamp = runCatching {
                        timestampParser.parse(match.groupValues[3])
                    }.getOrNull() ?: return@lineLoop
                    val method = match.groupValues[4]
                    val target = match.groupValues[5]
                    val status = match.groupValues[6].toIntOrNull() ?: 0
                    requestsByAddress.getOrPut(match.groupValues[2]) { mutableListOf() } +=
                        VisitRequest(
                            timestamp = timestamp.time,
                            isFile = isFileRequest(status),
                            isPage = isPageRequest(method, target, status),
                            isSuspicious = detectSuspicion(
                                method,
                                target,
                                match.groupValues[9]
                            ).isNotEmpty()
                        )
                }
            }
        }

        val selectedVisits = mutableListOf<VisitDetail>()
        requestsByAddress.forEach { (address, requests) ->
            requests.sortBy(VisitRequest::timestamp)
            var sessionStart = 0

            fun addSession(endExclusive: Int) {
                if (endExclusive <= sessionStart) return
                val session = requests.subList(sessionStart, endExclusive)
                if (dayFormatter.format(Date(session.first().timestamp)) != day) return
                selectedVisits += VisitDetail(
                    address = address,
                    firstTimestamp = session.first().timestamp,
                    lastTimestamp = session.last().timestamp,
                    hits = session.size,
                    files = session.count(VisitRequest::isFile),
                    pages = session.count(VisitRequest::isPage),
                    suspicious = session.count(VisitRequest::isSuspicious)
                )
            }

            for (index in 1 until requests.size) {
                if (requests[index].timestamp - requests[index - 1].timestamp > VISIT_TIMEOUT_MILLIS) {
                    addSession(index)
                    sessionStart = index
                }
            }
            addSession(requests.size)
        }

        selectedVisits.sortBy(VisitDetail::firstTimestamp)
        return DailyVisitDetails(
            totalCount = selectedVisits.size,
            visits = selectedVisits.take(maxVisits)
        )
    }

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
        val fileDayCounter = mutableMapOf<String, Int>()
        val pageDayCounter = mutableMapOf<String, Int>()
        val suspiciousDayCounter = mutableMapOf<String, Int>()
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

        files.forEachIndexed { sourceFileIndex, file ->
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
                    val fileRequest = isFileRequest(status)
                    val pageRequest = isPageRequest(method, url, status)

                    uniqueVisitors += ip
                    addressCounter[ip] = (addressCounter[ip] ?: 0) + 1
                    totalHits++

                    val addressRisk = riskByAddress.getOrPut(ip) { AddressRisk() }
                    addressRisk.totalHits++
                    addressRisk.sourceFiles += sourceFileIndex
                    if (status in 400..499) {
                        addressRisk.clientErrors++
                    }

                    val site = virtualHost ?: extractHost(url)
                    if (site != null) {
                        siteCounter[site] = (siteCounter[site] ?: 0) + 1
                    }

                    var parsedDay: String? = null
                    val timestamp = runCatching { timestampParser.parse(timestampRaw) }.getOrNull()
                    if (timestamp != null) {
                        val timestampMillis = timestamp.time
                        firstTimestamp = minOf(firstTimestamp ?: timestampMillis, timestampMillis)
                        lastTimestamp = maxOf(lastTimestamp ?: timestampMillis, timestampMillis)
                        timestampsByAddress.getOrPut(ip) { mutableListOf() } += timestampMillis
                        addressRisk.firstTimestamp = minOf(
                            addressRisk.firstTimestamp ?: timestampMillis,
                            timestampMillis
                        )
                        addressRisk.lastTimestamp = maxOf(
                            addressRisk.lastTimestamp ?: timestampMillis,
                            timestampMillis
                        )

                        val day = dayFormatter.format(timestamp)
                        parsedDay = day
                        val hour = hourFormatter.format(timestamp)
                        dayCounter[day] = (dayCounter[day] ?: 0) + 1
                        if (fileRequest) {
                            fileDayCounter[day] = (fileDayCounter[day] ?: 0) + 1
                        }
                        if (pageRequest) {
                            pageDayCounter[day] = (pageDayCounter[day] ?: 0) + 1
                        }
                        hourCounter[hour] = (hourCounter[hour] ?: 0) + 1
                    }

                    val suspicionReasons = detectSuspicion(method, url, userAgent)
                    if (suspicionReasons.isNotEmpty()) {
                        suspiciousRequestCount++
                        parsedDay?.let { day ->
                            suspiciousDayCounter[day] = (suspiciousDayCounter[day] ?: 0) + 1
                        }
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

                    if (pageRequest) {
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

        val visitDayCounter = mutableMapOf<String, Int>()
        timestampsByAddress.values.forEach { timestamps ->
            timestamps.sort()
            var previousTimestamp: Long? = null
            timestamps.forEach { timestamp ->
                val previous = previousTimestamp
                if (previous == null || timestamp - previous > VISIT_TIMEOUT_MILLIS) {
                    val visitDay = dayFormatter.format(Date(timestamp))
                    visitDayCounter[visitDay] = (visitDayCounter[visitDay] ?: 0) + 1
                }
                previousTimestamp = timestamp
            }
        }
        val totalVisits = visitDayCounter.values.sum()

        val dailyTraffic = dayCounter.keys.sorted().takeLast(MAX_DAILY_TRAFFIC_DAYS).map { day ->
            DailyTraffic(
                day = day,
                hits = dayCounter[day] ?: 0,
                files = fileDayCounter[day] ?: 0,
                pages = pageDayCounter[day] ?: 0,
                visits = visitDayCounter[day] ?: 0,
                suspicious = suspiciousDayCounter[day] ?: 0
            )
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
                sourceFileCount = risk.sourceFiles.size,
                firstTimestamp = risk.firstTimestamp,
                lastTimestamp = risk.lastTimestamp,
                reasons = reasons
            )
        }.sortedWith(
            compareByDescending<SuspiciousAddress> { it.suspiciousRequests }
                .thenByDescending { it.clientErrors }
        )
        val suspiciousAddresses = allSuspiciousAddresses.take(MAX_SUSPICIOUS_ADDRESSES)

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
            dailyTraffic = dailyTraffic,
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

    private fun isFileRequest(status: Int): Boolean = status in 200..399
}
