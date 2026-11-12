package com.smartdev.webalizer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.format.Formatter
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.FileProvider
import com.google.android.material.color.MaterialColors
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
    private var currentLogFiles: List<File> = emptyList()
    private var currentImportDirectory: File? = null

    private val openLogFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) importLogs(uris)
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

        listOf(topAddressesText, suspiciousAddressesText, suspiciousEventsText).forEach { textView ->
            textView.movementMethod = LinkMovementMethod.getInstance()
            textView.highlightColor = Color.TRANSPARENT
        }

        importButton.setOnClickListener {
            openFilePicker()
        }
    }

    private fun openFilePicker() {
        openLogFiles.launch(arrayOf("*/*"))
    }

    private fun importLogs(uris: List<Uri>) {
        setImporting(true)

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val importDirectory = File(
                        cacheDir,
                        "log-import-${System.nanoTime()}"
                    )
                    val sources = uris.map { uri ->
                        ImportSource(getDisplayName(uri)) {
                            contentResolver.openInputStream(uri)
                                ?: throw IllegalStateException(getString(R.string.file_open_failed))
                        }
                    }
                    val prepared = LogImportProcessor.prepare(
                        sources,
                        importDirectory
                    )

                    ImportedLog(
                        name = formatSourceNames(prepared.sourceNames),
                        sourceCount = prepared.sourceNames.size,
                        logFileCount = prepared.files.size,
                        archiveCount = prepared.archiveCount,
                        files = prepared.files,
                        importDirectory = importDirectory,
                        stats = WebalizerLogParser().parse(prepared.files)
                    )
                }
            }

            setImporting(false)
            result.onSuccess { importedLog ->
                val previousDirectory = currentImportDirectory
                currentImportDirectory = importedLog.importDirectory
                currentLogFiles = importedLog.files
                if (previousDirectory != null && previousDirectory != importedLog.importDirectory) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        previousDirectory.deleteRecursively()
                    }
                }
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

    private fun formatSourceNames(names: List<String>): String {
        return when {
            names.size <= 3 -> names.joinToString("\n")
            else -> getString(
                R.string.source_names_more,
                names.take(2).joinToString("\n"),
                names.size - 2
            )
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

        val formattedAddresses: CharSequence = if (stats.topAddresses.isEmpty()) {
            getString(R.string.no_address_data)
        } else {
            buildLinkedItems(
                stats.topAddresses,
                addressSelector = { it.first }
            ) { index, (address, count) ->
                getString(R.string.ranked_value_item, index + 1, address, numberFormat.format(count))
            }
        }

        val formattedSuspiciousAddresses: CharSequence = if (stats.suspiciousAddresses.isEmpty()) {
            getString(R.string.no_suspicious_addresses)
        } else {
            buildLinkedItems(
                stats.suspiciousAddresses,
                addressSelector = { it.address },
                onAddressClick = ::showSuspiciousAddressActions
            ) { index, item ->
                getString(
                    R.string.suspicious_address_item,
                    index + 1,
                    item.address,
                    numberFormat.format(item.suspiciousRequests),
                    numberFormat.format(item.clientErrors),
                    formatSuspicionReasons(item.reasons)
                )
            }
        }

        val formattedSuspiciousEvents: CharSequence = if (stats.suspiciousEvents.isEmpty()) {
            getString(R.string.no_suspicious_events)
        } else {
            buildLinkedItems(
                stats.suspiciousEvents.take(25),
                addressSelector = { it.address },
                onAddressClick = ::showSuspiciousAddressActions
            ) { index, event ->
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
            }
        }

        val formattedTopUrls = if (stats.topUrls.isEmpty()) {
            getString(R.string.no_page_views)
        } else {
            stats.topUrls.mapIndexed { index, (url, count) ->
                getString(R.string.top_url_item, index + 1, url, numberFormat.format(count))
            }.joinToString("\n\n")
        }

        fileNameText.text = importedLog.name
        fileMetaText.text = if (
            importedLog.sourceCount == 1 &&
            importedLog.logFileCount == 1 &&
            importedLog.archiveCount == 0
        ) {
            getString(
                R.string.file_meta,
                numberFormat.format(stats.parsedLines),
                numberFormat.format(stats.totalLines)
            )
        } else {
            getString(
                R.string.file_meta_multiple,
                numberFormat.format(importedLog.logFileCount),
                numberFormat.format(importedLog.archiveCount),
                numberFormat.format(stats.parsedLines),
                numberFormat.format(stats.totalLines)
            )
        }

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

    private fun <T> buildLinkedItems(
        items: List<T>,
        addressSelector: (T) -> String,
        onAddressClick: (String) -> Unit = ::showIpDetails,
        formatter: (Int, T) -> String
    ): CharSequence {
        val builder = SpannableStringBuilder()
        items.forEachIndexed { index, item ->
            if (index > 0) builder.append("\n\n")

            val text = formatter(index, item)
            val address = addressSelector(item)
            val itemStart = builder.length
            builder.append(text)

            val addressStart = text.indexOf(address)
            if (addressStart >= 0 && IpLookupService.isNumericAddress(address)) {
                builder.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            onAddressClick(address)
                        }

                        override fun updateDrawState(drawState: TextPaint) {
                            super.updateDrawState(drawState)
                            drawState.color = MaterialColors.getColor(
                                this@MainActivity,
                                com.google.android.material.R.attr.colorPrimary,
                                drawState.linkColor
                            )
                            drawState.isUnderlineText = true
                        }
                    },
                    itemStart + addressStart,
                    itemStart + addressStart + address.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return builder
    }

    private fun showSuspiciousAddressActions(address: String) {
        AlertDialog.Builder(this)
            .setTitle(address)
            .setItems(
                arrayOf(
                    getString(R.string.ip_action_access_log),
                    getString(R.string.ip_action_details)
                )
            ) { _, which ->
                when (which) {
                    0 -> showIpAccessLog(address)
                    1 -> showIpDetails(address)
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showIpAccessLog(address: String) {
        if (currentLogFiles.isEmpty()) {
            Toast.makeText(this, R.string.ip_log_sources_unavailable, Toast.LENGTH_LONG).show()
            return
        }

        val content = TextView(this).apply {
            setPadding(dp(24), dp(8), dp(24), dp(16))
            setTextIsSelectable(true)
            text = getString(R.string.ip_log_loading)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
        }
        val scrollView = ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.ip_log_title, address))
            .setView(scrollView)
            .setNeutralButton(R.string.copy, null)
            .setNegativeButton(R.string.share, null)
            .setPositiveButton(R.string.close, null)
            .create()
        importButton.isEnabled = false
        dialog.setOnDismissListener {
            if (importProgress.visibility != View.VISIBLE) importButton.isEnabled = true
        }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled = false
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).isEnabled = false
        }
        dialog.show()

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    IpAccessLogExporter.export(
                        currentLogFiles,
                        address,
                        File(cacheDir, "ip-logs")
                    )
                }
            }
            if (!dialog.isShowing) return@launch

            result.onSuccess { export ->
                content.text = buildString {
                    append(getString(R.string.ip_log_count, export.totalLines)).append("\n\n")
                    if (export.preview.isBlank()) {
                        append(getString(R.string.ip_log_empty))
                    } else {
                        append(export.preview)
                    }
                    if (export.previewTruncated) {
                        append("\n\n").append(getString(R.string.ip_log_preview_truncated))
                    }
                }

                val copyButton = dialog.getButton(DialogInterface.BUTTON_NEUTRAL)
                copyButton.isEnabled = export.totalLines > 0
                copyButton.setOnClickListener { copyAccessLog(export) }

                val shareButton = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
                shareButton.isEnabled = export.totalLines > 0
                shareButton.setOnClickListener { shareAccessLog(address, export.file) }
            }.onFailure { exception ->
                content.text = getString(
                    R.string.ip_log_error,
                    exception.message ?: getString(R.string.not_available)
                )
            }
        }
    }

    private fun copyAccessLog(export: IpAccessLogExport) {
        if (export.file.length() > MAX_CLIPBOARD_LOG_BYTES) {
            Toast.makeText(this, R.string.ip_log_copy_too_large, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { export.file.readText() }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(export.file.name, text))
            Toast.makeText(this@MainActivity, R.string.ip_log_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareAccessLog(address: String, file: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.ip_log_share_subject, address))
            putExtra(Intent.EXTRA_TEXT, getString(R.string.ip_log_ai_context, address))
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.ip_log_share_chooser)))
    }

    private fun showIpDetails(address: String) {
        val content = TextView(this).apply {
            setPadding(dp(24), dp(8), dp(24), dp(16))
            setTextIsSelectable(true)
            text = getString(R.string.ip_lookup_loading)
        }
        val scrollView = ScrollView(this).apply {
            addView(content)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.ip_details_title, address))
            .setView(scrollView)
            .setPositiveButton(R.string.close, null)
            .create()
        dialog.show()

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { IpLookupService.lookup(address) }
            }
            if (!dialog.isShowing) return@launch

            content.text = result.fold(
                onSuccess = { lookupResult ->
                    when (lookupResult) {
                        is IpLookupResult.Local -> getString(
                            R.string.ip_lookup_local,
                            lookupResult.description
                        )
                        is IpLookupResult.Public -> formatIpDetails(lookupResult.details)
                    }
                },
                onFailure = { exception ->
                    getString(
                        R.string.ip_lookup_error,
                        exception.message ?: getString(R.string.not_available)
                    )
                }
            )
        }
    }

    private fun formatIpDetails(details: IpDetails): String = buildString {
        fun section(title: String) {
            if (isNotEmpty()) append("\n\n")
            append(title).append('\n')
        }

        fun value(label: String, value: Any?) {
            val text = value?.toString()?.takeIf(String::isNotBlank) ?: return
            append(label).append(": ").append(text).append('\n')
        }

        section(getString(R.string.ip_location_section))
        value("Land", listOfNotNull(details.country, details.countryCode).joinToString(" · "))
        value("Region", details.region)
        value("Stadt", details.city)
        value("Postleitzahl", details.postalCode)
        value("Kontinent", details.continent)
        value("Zeitzone", details.timezone)
        if (details.latitude != null && details.longitude != null) {
            value(
                "Koordinaten",
                String.format(Locale.getDefault(), "%.5f, %.5f", details.latitude, details.longitude)
            )
        }

        section(getString(R.string.ip_network_section))
        value("IP-Typ", details.addressType)
        value("ASN", details.asn?.let { "AS$it" })
        value("Organisation", details.organization)
        value("Provider", details.isp)
        value("Domain", details.domain)

        section(getString(R.string.ip_rdap_section))
        value("Netzname", details.networkName)
        value("Handle", details.networkHandle)
        value(
            "Adressbereich",
            if (details.networkStart != null && details.networkEnd != null) {
                "${details.networkStart} – ${details.networkEnd}"
            } else null
        )
        value("Netztyp", details.networkType)
        value("Registrierungsland", details.networkCountry)
        value("Inhaber", details.registrant)
        value("Abuse-Kontakt", details.abuseContact)
        value("Registry-Server", details.registryServer)

        if (details.unavailableSources.isNotEmpty()) {
            append("\n")
            append(getString(R.string.ip_lookup_partial, details.unavailableSources.joinToString(", ")))
        }
        append("\n\n").append(getString(R.string.ip_lookup_privacy_note))
    }.trim()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        val sourceCount: Int,
        val logFileCount: Int,
        val archiveCount: Int,
        val files: List<File>,
        val importDirectory: File,
        val stats: LogStats
    )

    private companion object {
        const val MAX_CLIPBOARD_LOG_BYTES = 1L * 1024 * 1024
    }
}
