package com.smartdev.webalizer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

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

        val btnImport = findViewById<Button>(R.id.btnImport)
        btnImport.setOnClickListener {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val shouldShowRationale = permissions.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (shouldShowRationale) {
                requestPermissionLauncher.launch(permissions)
            } else {
                openFilePicker()
            }
        }
    }

    private fun openFilePicker() {
        val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
        intent.type = "text/*"
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent, 1)
    }

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
            Toast.makeText(this, "Zeilen: ${stats.totalLines}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
