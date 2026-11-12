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
import java.text.NumberFormat

class MainActivity : AppCompatActivity() {

    private lateinit var importButton: Button
    private lateinit var importProgress: ProgressBar
    private lateinit var emptyState: TextView
    private lateinit var resultContent: LinearLayout
    private lateinit var fileNameText: TextView
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

        val formattedTopUrls = if (stats.topUrls.isEmpty()) {
            getString(R.string.no_page_views)
        } else {
            stats.topUrls.mapIndexed { index, (url, count) ->
                getString(R.string.top_url_item, index + 1, url, numberFormat.format(count))
            }.joinToString("\n\n")
        }

        fileNameText.text = importedLog.name
        bindStat(R.id.totalLinesRow, R.string.total_lines, numberFormat.format(stats.totalLines))
        bindStat(R.id.parsedLinesRow, R.string.parsed_lines, numberFormat.format(stats.parsedLines))
        bindStat(R.id.visitorsRow, R.string.visitors, numberFormat.format(stats.totalVisitors))
        bindStat(R.id.hitsRow, R.string.total_hits, numberFormat.format(stats.totalHits))
        bindStat(R.id.pagesRow, R.string.page_views, numberFormat.format(stats.totalPages))
        bindStat(
            R.id.bandwidthRow,
            R.string.total_bandwidth,
            Formatter.formatFileSize(this, stats.totalBandwidth)
        )
        topUrlsText.text = formattedTopUrls

        emptyState.visibility = View.GONE
        resultContent.visibility = View.VISIBLE
    }

    private fun bindStat(rowId: Int, labelResource: Int, value: String) {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.statLabel).setText(labelResource)
        row.findViewById<TextView>(R.id.statValue).text = value
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
