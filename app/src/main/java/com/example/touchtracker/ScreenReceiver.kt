package com.example.touchtracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

class ScreenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_USER_PRESENT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    !Settings.canDrawOverlays(context)
                ) {
                    Log.w(TAG, "Нет overlay-разрешения")
                    return
                }
                val serviceIntent = Intent(context, TouchTrackerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
            Intent.ACTION_SCREEN_OFF -> {
                context.stopService(Intent(context, TouchTrackerService::class.java))
            }
        }
    }

    companion object {
        private const val TAG = "ScreenReceiver"
    }
}
