package com.arcle.intelligence.telemetry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service for uploading telemetry batches to Supabase.
 * Runs briefly when internet becomes available, uploads pending batches, then stops.
 */
class TelemetryUploadService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    companion object {
        private const val CHANNEL_ID = "arcle_telemetry_upload"
        private const val NOTIFICATION_ID = 3001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Arcle Intelligence")
            .setContentText("Syncing usage data...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        scope.launch {
            try {
                val manager = TelemetryBatchManager.getInstance(applicationContext)
                manager.uploadPendingBatchesIfAvailable()
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Telemetry Upload",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background telemetry sync"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
