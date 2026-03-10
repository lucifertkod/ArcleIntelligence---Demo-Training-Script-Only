package com.arcle.intelligence.telemetry

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TelemetryDao {

    @Insert
    suspend fun insertBatch(batch: TelemetryBatch)

    @Query("SELECT * FROM telemetry_batches WHERE uploaded = 0 ORDER BY createdAt ASC")
    suspend fun getUnuploadedBatches(): List<TelemetryBatch>

    @Query("SELECT * FROM telemetry_batches ORDER BY createdAt ASC")
    suspend fun getAllBatches(): List<TelemetryBatch>

    @Query("UPDATE telemetry_batches SET uploaded = 1 WHERE batchId = :batchId")
    suspend fun markBatchUploaded(batchId: Int)

    @Query("DELETE FROM telemetry_batches WHERE batchId = :batchId")
    suspend fun deleteBatch(batchId: Int)

    @Query("SELECT COUNT(*) FROM telemetry_batches")
    suspend fun getBatchCount(): Int
}
