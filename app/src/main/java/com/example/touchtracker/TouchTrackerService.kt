package com.example.touchtracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground-сервис с прозрачным overlay.
 * Логирует касания в CSV (append), с throttle для MOVE и периодическим flush.
 */
class TouchTrackerService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: FrameLayout? = null

    private lateinit var logFile: File
    private var writer: BufferedWriter? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val handler = Handler(Looper.getMainLooper())

    private val writeQueue = ConcurrentLinkedQueue<String>()
    private val isFlushing = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)

    // Throttle MOVE
    private var lastMoveTime = 0L
    private var lastMoveX = Float.NaN
    private var lastMoveY = Float.NaN
    private val moveMinIntervalMs = 16L
    private val moveMinDeltaPx = 2f

    private var autoStopMs = 0L

    private val flushRunnable = object : Runnable {
        override fun run() {
            flushQueue()
            if (isRunning.get()) {
                handler.postDelayed(this, FLUSH_INTERVAL_MS)
            }
        }
    }

    private val autoStopRunnable = Runnable {
        Log.i(TAG, "Авто-стоп по таймеру")
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val dir = getExternalFilesDir(null) ?: filesDir
        logFile = File(dir, LOG_FILE_NAME)

        openLogWriter()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            Log.e(TAG, "Нет разрешения overlay — останавливаемся")
            stopSelf()
            return
        }

        createOverlay()
        isRunning.set(true)
        handler.post(flushRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        autoStopMs = intent?.getLongExtra(EXTRA_AUTO_STOP_MS, 0L) ?: 0L
        handler.removeCallbacks(autoStopRunnable)
        if (autoStopMs > 0) {
            handler.postDelayed(autoStopRunnable, autoStopMs)
        }
        return START_STICKY
    }

    private fun openLogWriter() {
        try {
            val isNew = !logFile.exists() || logFile.length() == 0L
            writer = BufferedWriter(FileWriter(logFile, /* append = */ true), 8192)
            if (isNew) {
                writer?.apply {
                    write(CSV_HEADER)
                    newLine()
                    flush()
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Не удалось открыть лог-файл", e)
            writer = null
        }
    }

    private fun createOverlay() {
        try {
            overlayView = FrameLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setOnTouchListener { _, event -> handleTouch(event) }
            }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            windowManager.addView(overlayView, params)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException при addView", e)
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка создания overlay", e)
            stopSelf()
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                logPointer(event, event.actionIndex, "DOWN")
                lastMoveX = Float.NaN
                lastMoveY = Float.NaN
            }

            MotionEvent.ACTION_MOVE -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastMoveTime < moveMinIntervalMs) return false

                val x = event.getX(0)
                val y = event.getY(0)
                if (!lastMoveX.isNaN()) {
                    val dx = x - lastMoveX
                    val dy = y - lastMoveY
                    if (dx * dx + dy * dy < moveMinDeltaPx * moveMinDeltaPx) {
                        return false
                    }
                }

                lastMoveTime = now
                lastMoveX = x
                lastMoveY = y

                for (i in 0 until event.pointerCount) {
                    logPointer(event, i, "MOVE")
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                logPointer(event, event.actionIndex, "UP")
            }

            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until event.pointerCount) {
                    logPointer(event, i, "CANCEL")
                }
            }
        }
        // false = не перехватываем, событие уходит в приложение под overlay
        return false
    }

    private fun logPointer(event: MotionEvent, pointerIndex: Int, action: String) {
        try {
            val id = event.getPointerId(pointerIndex)
            val x = event.getX(pointerIndex)
            val y = event.getY(pointerIndex)
            val time = timeFormat.format(Date())
            writeQueue.offer(
                "$time,$id,${"%.1f".format(Locale.US, x)},${"%.1f".format(Locale.US, y)},$action,${event.pointerCount}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка формирования записи", e)
        }
    }

    private fun flushQueue() {
        if (!isFlushing.compareAndSet(false, true)) return
        val w = writer ?: run {
            isFlushing.set(false)
            return
        }
        try {
            var line: String?
            var count = 0
            while (writeQueue.poll().also { line = it } != null) {
                w.write(line)
                w.newLine()
                count++
            }
            if (count > 0) {
                w.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Ошибка записи в лог", e)
        } finally {
            isFlushing.set(false)
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "touch_tracker"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        getString(R.string.notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = getString(R.string.notification_channel_desc)
                    }
                )
            }
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning.set(false)
        handler.removeCallbacks(flushRunnable)
        handler.removeCallbacks(autoStopRunnable)

        flushQueue()

        try {
            writer?.flush()
            writer?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Ошибка закрытия writer", e)
        }
        writer = null

        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TouchTrackerService"
        private const val NOTIF_ID = 1
        private const val FLUSH_INTERVAL_MS = 400L
        private const val LOG_FILE_NAME = "touches_log.csv"
        private const val CSV_HEADER = "time,pointerId,x,y,action,pointerCount"

        const val EXTRA_AUTO_STOP_MS = "auto_stop_ms"
    }
}
