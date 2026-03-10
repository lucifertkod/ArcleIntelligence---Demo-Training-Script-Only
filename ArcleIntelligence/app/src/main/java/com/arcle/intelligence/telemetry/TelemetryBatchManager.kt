package com.arcle.intelligence.telemetry

import android.content.Context
import android.util.Log
import androidx.work.*
import com.arcle.intelligence.memory.ChatDatabase
import com.arcle.intelligence.utils.Constants
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Telemetry Batch Manager.
 * Collects anonymized usage data in batches and uploads silently to Supabase.
 * User can opt-out via settings.
 */
class TelemetryBatchManager(private val context: Context) {

    companion object {
        private const val TAG = "TelemetryBatchManager"
        private const val PREF_TELEMETRY_ENABLED = "telemetry_enabled"
    }

    data class TelemetryEvent(
        val intentTag: String,
        val timestamp: Long,
        val responseTimeMs: Long,
        val emotion: String,
        val success: Boolean
    )

    private val eventBuffer = mutableListOf<TelemetryEvent>()
    private val gson = Gson()

    fun recordEvent(
        intentTag: String,
        responseTimeMs: Long,
        emotion: String,
        success: Boolean
    ) {
        if (!isTelemetryEnabled()) return

        eventBuffer.add(TelemetryEvent(
            intentTag = intentTag,
            timestamp = System.currentTimeMillis(),
            responseTimeMs = responseTimeMs,
            emotion = emotion,
            success = success
        ))

        if (eventBuffer.size >= Constants.TELEMETRY_BATCH_SIZE) {
            flushBatch()
        }
    }

    private fun flushBatch() {
        if (eventBuffer.isEmpty()) return

        val batch = eventBuffer.toList()
        eventBuffer.clear()

        val batchJson = gson.toJson(batch)

        val workRequest = OneTimeWorkRequestBuilder<TelemetryUploadWorker>()
            .setInputData(workDataOf("batch_data" to batchJson))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)
    }

    fun schedulePeriodic() {
        val periodicWork = PeriodicWorkRequestBuilder<TelemetryUploadWorker>(
            6, TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "arcle_telemetry",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWork
        )
    }

    fun isTelemetryEnabled(): Boolean {
        val prefs = context.getSharedPreferences("arcle_settings", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_TELEMETRY_ENABLED, true)
    }

    fun setTelemetryEnabled(enabled: Boolean) {
        val prefs = context.getSharedPreferences("arcle_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_TELEMETRY_ENABLED, enabled).apply()
    }
}

class TelemetryUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "TelemetryUploadWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val batchData = inputData.getString("batch_data") ?: return@withContext Result.success()

        try {
            uploadToSupabase(batchData)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Telemetry upload failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun uploadToSupabase(batchData: String) {
        val url = URL("${Constants.SUPABASE_URL}/rest/v1/${Constants.SUPABASE_TABLE_NAME}")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("apikey", Constants.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer ${Constants.SUPABASE_ANON_KEY}")
            connection.setRequestProperty("Prefer", "return=minimal")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val payload = """{"batch_data": $batchData, "uploaded_at": "${java.time.Instant.now()}"}"""
            connection.outputStream.use { os ->
                os.write(payload.toByteArray())
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw Exception("Supabase upload failed with code $responseCode")
            }

            Log.i(TAG, "Telemetry batch uploaded successfully")
        } finally {
            connection.disconnect()
        }
    }
}
