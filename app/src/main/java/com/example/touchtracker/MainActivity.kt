package com.example.touchtracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.touchtracker.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    R.string.toast_notification_denied,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPermission.setOnClickListener { requestOverlayPermission() }
        binding.btnStart.setOnClickListener { startServiceManually(autoStopMs = 10_000L) }
        binding.btnStop.setOnClickListener {
            stopService(Intent(this, TouchTrackerService::class.java))
            Toast.makeText(this, R.string.toast_service_stopped, Toast.LENGTH_SHORT).show()
            updateStatus()
        }
        binding.btnOpenLog.setOnClickListener { shareLogFile() }

        ensureNotificationPermission()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                Toast.makeText(this, R.string.toast_permission_already, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startServiceManually(autoStopMs: Long = 0L) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_permission_needed, Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }

        val intent = Intent(this, TouchTrackerService::class.java).apply {
            putExtra(TouchTrackerService.EXTRA_AUTO_STOP_MS, autoStopMs)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        val msg = if (autoStopMs > 0) {
            getString(R.string.toast_service_started, autoStopMs / 1000)
        } else {
            getString(R.string.toast_service_started_indefinite)
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    private fun updateStatus() {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        binding.statusText.setText(
            if (hasOverlay) R.string.status_overlay_ok
            else R.string.status_overlay_missing
        )

        val logFile = File(getExternalFilesDir(null), "touches_log.csv")
        binding.logInfo.text = if (logFile.exists() && logFile.length() > 0) {
            "Лог: ${logFile.name}\n" +
                    "Размер: ${"%.1f".format(logFile.length() / 1024.0)} КБ\n" +
                    logFile.absolutePath
        } else {
            getString(R.string.log_empty)
        }
    }

    private fun shareLogFile() {
        val logFile = File(getExternalFilesDir(null), "touches_log.csv")
        if (!logFile.exists() || logFile.length() == 0L) {
            Toast.makeText(this, R.string.toast_log_empty, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                logFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_log_title)))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Не удалось открыть файл:\n${logFile.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
