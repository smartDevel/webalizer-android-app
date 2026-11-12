package com.smartdev.webalizer

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var resultText: TextView

    private val openLogFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }

        try {
            val inputStream = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Datei konnte nicht geöffnet werden")
            val logFile = File(cacheDir, "webalizer.log")

            inputStream.use { input ->
                logFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            analyzeLog(logFile)
        } catch (exception: Exception) {
            Toast.makeText(
                this,
                "Fehler beim Öffnen: ${exception.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        resultText = findViewById(R.id.resultText)

        findViewById<Button>(R.id.btnImport).setOnClickListener {
            openLogFile.launch(arrayOf("*/*"))
        }
    }

    private fun analyzeLog(file: File) {
        try {
            val parser = WebalizerLogParser()
            val stats = parser.parse(file)

            val topUrlsText = if (stats.topUrls.isEmpty()) {
                "Keine Seitenaufrufe erkannt"
            } else {
                stats.topUrls.joinToString("\n") { "- ${it.first} (${it.second})" }
            }

            resultText.text = """
                Zeilen gesamt: ${stats.totalLines}
                Zeilen geparst: ${stats.parsedLines}
                Besucher (unique IP): ${stats.totalVisitors}
                Hits gesamt: ${stats.totalHits}
                Seitenaufrufe: ${stats.totalPages}
                Bandbreite gesamt: ${stats.totalBandwidth} Bytes

                Top 5 URLs:
                $topUrlsText
            """.trimIndent()
        } catch (exception: Exception) {
            Toast.makeText(this, "Fehler: ${exception.message}", Toast.LENGTH_LONG).show()
        }
    }
}
