package com.smartdev.webalizer

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var importButton: Button
    private lateinit var importProgress: ProgressBar
    private lateinit var emptyState: TextView
    private lateinit var resultContent: LinearLayout
    private lateinit var fileNameText: TextView
    private lateinit var fileMetaText: TextView
    private lateinit var dailyHitsText: TextView
    private lateinit var securitySummaryText: TextView
    private lateinit var suspiciousAddressesText: TextView
    private lateinit var suspiciousEventsText: TextView
    private lateinit var topAddressesText: TextView
    private lateinit var topUrlsText: TextView

    private var hasResult = false

    private val openLogFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }

        importLog(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        importButton = findViewById(R.id.btnImport)
        importProgress = findViewById(R.id.importProgress)
        emptyState = findViewById(R.id.emptyState)
        resultContent = findViewById(R.id.resultContent)
        fileNameText = findViewById(R.id.fileNameText)
        fileMetaText = findViewById(R.id.fileMetaText)
        dailyHitsText = findViewById(R.id.dailyHitsText)
        securitySummaryText = findViewById(R.id.securitySummaryText)
        suspiciousAddressesText = findViewById(R.id.suspiciousAddressesText)
        suspiciousEventsText = findViewById(R.id.suspiciousEventsText)
        topAddressesText = findViewById(R.id.topAddressesText)
        topUrlsText = findViewById(R.id.topUrlsText)

        importButton.setOnClickListener {
            openFilePicker()
        }
    }

    private fun openFilePicker() {
        openLogFile.launch(arrayOf("*/*"))
    }

    private fun importLog(uri: Uri) {
        setImporting(true)

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val logFile = File(cacheDir, "webalizer.log")
                    val inputStream = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException(getString(R.string.file_open_failed))

                    inputStream.use { input ->
                        logFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    ImportedLog(
                        name = getDisplayName(uri),
                        stats = WebalizerLogParser().parse(logFile)
                    )
                }
            }

            setImporting(false)
            result.onSuccess { importedLog ->
                hasResult = true
                showAnalysis(importedLog)
            }
                .onFailure { exception ->
                    if (!hasResult) {
                        emptyState.visibility = View.VISIBLE
                    }
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.file_open_error, exception.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun getDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(nameColumn)
                }
            }

        return getString(R.string.unknown_file_name)
    }

    private fun showAnalysis(importedLog: ImportedLog) {
        val stats = importedLog.stats
        val numberFormat = NumberFormat.getIntegerInstance()
        val decimalFormat = NumberFormat.getNumberInstance().apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 1
        }

        val formattedDailyHits = if (stats.hitsByDay.isEmpty()) {
            getString(R.string.no_time_data)
        } else {
            stats.hitsByDay.joinToString("\n") { (day, count) ->
                getString(R.string.list_value_item, formatDay(day), numberFormat.format(count))
            }
        }

        val formattedAddresses = if (stats.topAddresses.isEmpty()) {
            getString(R.string.no_address_data)
        } else {
            stats.topAddresses.mapIndexed { index, (address, count) ->
                getString(R.string.ranked_value_item, index + 1, address, numberFormat.format(count))
            }.joinToString("\n\n")
        }

        val formattedSuspiciousAddresses = if (stats.suspiciousAddresses.isEmpty()) {
            getString(R.string.no_suspicious_addresses)
        } else {
            stats.suspiciousAddresses.mapIndexed { index, item ->
                getString(
                    R.string.suspicious_address_item,
                    index + 1,
                    item.address,
                    numberFormat.format(item.suspiciousRequests),
                    numberFormat.format(item.clientErrors),
                    formatSuspicionReasons(item.reasons)
                )
            }.joinToString("\n\n")
        }

        val formattedSuspiciousEvents = if (stats.suspiciousEvents.isEmpty()) {
            getString(R.string.no_suspicious_events)
        } else {
            stats.suspiciousEvents.take(25).mapIndexed { index, event ->
                getString(
                    R.string.suspicious_event_item,
                    index + 1,
                    formatTimestamp(event.timestamp),
                    event.address,
                    event.method,
                    event.target,
                    event.status,
                    formatSuspicionReasons(event.reasons)
                )
            }.joinToString("\n\n")
        }

        val formattedTopUrls = if (stats.topUrls.isEmpty()) {
            getString(R.string.no_page_views)
        } else {
            stats.topUrls.mapIndexed { index, (url, count) ->
                getString(R.string.top_url_item, index + 1, url, numberFormat.format(count))
            }.joinToString("\n\n")
        }

        fileNameText.text = importedLog.name
        fileMetaText.text = getString(
            R.string.file_meta,
            numberFormat.format(stats.parsedLines),
            numberFormat.format(stats.totalLines)
        )

        bindStat(R.id.siteRow, R.string.site, stats.site ?: getString(R.string.site_not_available))
        bindStat(
            R.id.periodStartRow,
            R.string.period_start,
            formatTimestamp(stats.firstTimestamp)
        )
        bindStat(
            R.id.periodEndRow,
            R.string.period_end,
            formatTimestamp(stats.lastTimestamp)
        )
        bindStat(R.id.activeDaysRow, R.string.active_days, numberFormat.format(stats.activeDays))

        bindStat(R.id.visitsRow, R.string.visits, numberFormat.format(stats.totalVisits))
        bindStat(R.id.visitorsRow, R.string.visitors, numberFormat.format(stats.totalVisitors))
        bindStat(R.id.hitsRow, R.string.total_hits, numberFormat.format(stats.totalHits))
        bindStat(R.id.pagesRow, R.string.page_views, numberFormat.format(stats.totalPages))
        bindStat(
            R.id.bandwidthRow,
            R.string.total_bandwidth,
            Formatter.formatFileSize(this, stats.totalBandwidth)
        )

        bindStat(
            R.id.averageDayRow,
            R.string.average_hits_day,
            if (stats.activeDays > 0) {
                decimalFormat.format(stats.totalHits.toDouble() / stats.activeDays)
            } else {
                getString(R.string.not_available)
            }
        )
        bindStat(
            R.id.averageHourRow,
            R.string.average_hits_hour,
            if (stats.activeHours > 0) {
                decimalFormat.format(stats.totalHits.toDouble() / stats.activeHours)
            } else {
                getString(R.string.not_available)
            }
        )
        bindStat(
            R.id.busiestDayRow,
            R.string.busiest_day,
            stats.busiestDay?.let { (day, count) ->
                getString(R.string.peak_value, formatDay(day), numberFormat.format(count))
            } ?: getString(R.string.not_available)
        )
        bindStat(
            R.id.busiestHourRow,
            R.string.busiest_hour,
            stats.busiestHour?.let { (hour, count) ->
                getString(R.string.peak_value, formatHour(hour), numberFormat.format(count))
            } ?: getString(R.string.not_available)
        )

        dailyHitsText.text = formattedDailyHits
        securitySummaryText.text = if (stats.suspiciousAddressCount == 0) {
            getString(R.string.security_summary_clear)
        } else {
            getString(
                R.string.security_summary_flagged,
                numberFormat.format(stats.suspiciousRequestCount),
                numberFormat.format(stats.suspiciousAddressCount)
            )
        }
        suspiciousAddressesText.text = formattedSuspiciousAddresses
        suspiciousEventsText.text = formattedSuspiciousEvents
        topAddressesText.text = formattedAddresses
        topUrlsText.text = formattedTopUrls

        emptyState.visibility = View.GONE
        resultContent.visibility = View.VISIBLE
    }

    private fun bindStat(rowId: Int, labelResource: Int, value: String) {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.statLabel).setText(labelResource)
        row.findViewById<TextView>(R.id.statValue).text = value
    }

    private fun formatTimestamp(timestamp: Long?): String {
        if (timestamp == null) return getString(R.string.not_available)
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
    }

    private fun formatDay(day: String): String {
        val date = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(day)
        }.getOrNull() ?: return day
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
    }

    private fun formatHour(hour: String): String {
        val date = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).parse(hour)
        }.getOrNull() ?: return hour
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(date)
    }

    private fun formatSuspicionReasons(reasons: Set<SuspicionReason>): String {
        return reasons.joinToString(", ") { reason ->
            getString(
                when (reason) {
                    SuspicionReason.SENSITIVE_PATH -> R.string.reason_sensitive_path
                    SuspicionReason.ATTACK_PAYLOAD -> R.string.reason_attack_payload
                    SuspicionReason.SCANNER_USER_AGENT -> R.string.reason_scanner_user_agent
                    SuspicionReason.UNUSUAL_METHOD -> R.string.reason_unusual_method
                    SuspicionReason.ERROR_BURST -> R.string.reason_error_burst
                }
            )
        }
    }

    private fun setImporting(isImporting: Boolean) {
        importProgress.visibility = if (isImporting) View.VISIBLE else View.GONE
        importButton.isEnabled = !isImporting
        if (isImporting && !hasResult) {
            emptyState.visibility = View.GONE
        }
    }

    private data class ImportedLog(
        val name: String,
        val stats: LogStats
    )
}
