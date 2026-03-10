package com.arcle.intelligence.services

import android.app.*
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.arcle.intelligence.ai.SherpaKwsEngine
import kotlinx.coroutines.*

/**
 * KWS Foreground Service.
 * Runs continuously to listen for wake words ("hey arcle", "ok arcle", etc.)
 * Starts at boot and runs as a persistent foreground service.
 */
class KwsService : Service(), SherpaKwsEngine.WakeWordListener {

    companion object {
        private const val TAG = "KwsService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "arcle_kws_channel"
        private const val SAMPLE_RATE = 16000
        const val ACTION_WAKE_WORD_DETECTED = "com.arcle.intelligence.WAKE_WORD_DETECTED"
    }

    private var kwsEngine: SherpaKwsEngine? = null
    private var isRunning = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioRecord: AudioRecord? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        serviceScope.launch {
            kwsEngine = SherpaKwsEngine(this@KwsService)
            kwsEngine?.setListener(this@KwsService)
            kwsEngine?.initialize()
            startAudioLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onWakeWordDetected(keyword: String) {
        Log.i(TAG, "Wake word detected: $keyword")
        val intent = Intent(ACTION_WAKE_WORD_DETECTED).apply {
            putExtra("keyword", keyword)
        }
        sendBroadcast(intent)
    }

    private fun startAudioLoop() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                bufferSize * 2
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission not granted", e)
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            return
        }

        isRunning = true
        kwsEngine?.startListening()
        audioRecord?.startRecording()

        serviceScope.launch {
            val buffer = FloatArray(bufferSize / 4)
            while (isRunning) {
                val read = audioRecord?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: 0
                if (read > 0) {
                    kwsEngine?.feedAudioSamples(buffer.copyOf(read))
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Arcle Wake Word Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Listening for wake words"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Arcle Intelligence")
                .setContentText("Listening for your voice...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Arcle Intelligence")
                .setContentText("Listening for your voice...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        isRunning = false
        audioRecord?.stop()
        audioRecord?.release()
        kwsEngine?.release()
        serviceScope.cancel()
        super.onDestroy()
    }
}
