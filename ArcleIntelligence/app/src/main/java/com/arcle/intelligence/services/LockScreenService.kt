package com.arcle.intelligence.services

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.*

/**
 * Lock Screen Service — minimal overlay on lock screen.
 * Responds to incoming calls, time queries, and basic voice commands
 * without requiring full unlock. Uses SYSTEM_ALERT_WINDOW.
 */
class LockScreenService : Service() {

    companion object {
        private const val TAG = "LockScreenService"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "arcle_lockscreen", "Lock Screen",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .createNotificationChannel(channel)

            val notification = android.app.Notification.Builder(this, "arcle_lockscreen")
                .setContentTitle("Arcle Intelligence")
                .setContentText("Lock screen active")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
            startForeground(1002, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showOverlay()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay() {
        if (overlayView != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        overlayView = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#CC0A0A0C"))
            setPadding(32, 24, 32, 48)
        }

        // Waveform indicator (simple text placeholder)
        val statusText = TextView(this).apply {
            text = "🎤 Arcle Intelligence — Say \"Hey Arcle\""
            setTextColor(android.graphics.Color.parseColor("#6E56CF"))
            textSize = 14f
            gravity = Gravity.CENTER
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = params
        }
        overlayView!!.addView(statusText)

        try {
            windowManager?.addView(overlayView, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing lock screen overlay", e)
        }
    }

    private fun removeOverlay() {
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay", e)
            }
            overlayView = null
        }
    }

    fun getCurrentTime(): String {
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
        return "Sir, it is ${sdf.format(Date())}."
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }
}
