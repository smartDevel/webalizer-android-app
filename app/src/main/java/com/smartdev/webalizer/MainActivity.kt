package com.smartdev.webalizer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var resultText: TextView

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            openFilePicker()
        } else {
            Toast.makeText(this, "Berechtigungen werden benötigt", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        resultText = findViewById(R.id.resultText)

        val btnImport = findViewById<Button>(R.id.btnImport)
        btnImport.setOnClickListener {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val shouldAskPermissions = permissions.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (shouldAskPermissions) {
                requestPermissionLauncher.launch(permissions)
            } else {
                openFilePicker()
            }
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "text/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                val inputStream = contentResolver.openInputStream(uri)
                val logFile = File(cacheDir, "webalizer.log")
                inputStream?.use { input ->
                    logFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                analyzeLog(logFile)
            }
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
        } catch (e: Exception) {
            Toast.makeText(this, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
