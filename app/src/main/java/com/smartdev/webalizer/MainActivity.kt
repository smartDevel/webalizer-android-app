package com.smartdev.webalizer

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
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

class MainActivity : AppCompatActivity() {

    private lateinit var resultText: TextView
    private lateinit var localImportButton: Button
    private lateinit var driveImportButton: Button
    private lateinit var importProgress: ProgressBar

    private var pendingImportSource = ImportSource.LOCAL

    private val openLogFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }

        importLog(uri, pendingImportSource)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        resultText = findViewById(R.id.resultText)
        localImportButton = findViewById(R.id.btnImport)
        driveImportButton = findViewById(R.id.btnDriveImport)
        importProgress = findViewById(R.id.importProgress)

        localImportButton.setOnClickListener {
            openFilePicker(ImportSource.LOCAL)
        }

        driveImportButton.setOnClickListener {
            Toast.makeText(this, R.string.drive_picker_hint, Toast.LENGTH_LONG).show()
            openFilePicker(ImportSource.GOOGLE_DRIVE)
        }
    }

    private fun openFilePicker(source: ImportSource) {
        pendingImportSource = source
        openLogFile.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
    }

    private fun importLog(uri: Uri, source: ImportSource) {
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
                        source = source,
                        stats = WebalizerLogParser().parse(logFile)
                    )
                }
            }

            setImporting(false)
            result.onSuccess(::showAnalysis)
                .onFailure { exception ->
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

        val topUrlsText = if (stats.topUrls.isEmpty()) {
            getString(R.string.no_page_views)
        } else {
            stats.topUrls.joinToString("\n") { "- ${it.first} (${it.second})" }
        }

        resultText.text = """
                Quelle: ${getString(importedLog.source.labelResource)}
                Datei: ${importedLog.name}

                Zeilen gesamt: ${stats.totalLines}
                Zeilen geparst: ${stats.parsedLines}
                Besucher (unique IP): ${stats.totalVisitors}
                Hits gesamt: ${stats.totalHits}
                Seitenaufrufe: ${stats.totalPages}
                Bandbreite gesamt: ${stats.totalBandwidth} Bytes

                Top 5 URLs:
                $topUrlsText
            """.trimIndent()
    }

    private fun setImporting(isImporting: Boolean) {
        importProgress.visibility = if (isImporting) View.VISIBLE else View.GONE
        localImportButton.isEnabled = !isImporting
        driveImportButton.isEnabled = !isImporting
        if (isImporting) {
            resultText.setText(R.string.loading_file)
        }
    }

    private enum class ImportSource(val labelResource: Int) {
        LOCAL(R.string.source_local),
        GOOGLE_DRIVE(R.string.source_google_drive)
    }

    private data class ImportedLog(
        val name: String,
        val source: ImportSource,
        val stats: LogStats
    )
}
