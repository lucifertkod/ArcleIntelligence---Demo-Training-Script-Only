package com.arcle.intelligence.telemetry

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Stored locally in Room DB until uploaded to Supabase.
 */
@Entity(tableName = "telemetry_batches")
data class TelemetryBatch(
    @PrimaryKey(autoGenerate = true) val batchId: Int = 0,
    val batchData: String,          // JSON-serialized list of TelemetryRecord
    val createdAt: Long,
    val messageCount: Int,
    val uploaded: Boolean = false
)

/**
 * One record = one complete user interaction.
 */
@Serializable
data class TelemetryRecord(
    val timestamp: Long,
    val userInput: String,
    val detectedIntent: String,
    val enhancedPromptUsed: String,
    val arcleResponse: String,
    val actionsExecuted: List<String>,
    val executionTimeMs: Long,
    val detectedEmotion: String,
    val modelUsed: String,
    val deviceId: String
)

/**
 * Shape sent to Supabase table.
 */
@Serializable
data class SupabaseBatchPayload(
    val device_id: String,
    val batch_data: List<TelemetryRecord>
)
