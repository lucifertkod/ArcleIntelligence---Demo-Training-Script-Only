package com.arcle.intelligence.memory

import android.content.Context
import androidx.room.*

@Entity(tableName = "chat_history")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val role: String,               // "user" or "assistant"
    val content: String,
    val timestamp: Long,
    val intentTag: String,          // e.g., "[CHAT]", "[IMAGE]"
    val detectedEmotion: String,    // e.g., "HAPPY", "SAD", "NEUTRAL"
    val emotionConfidence: Float,   // 0.0 to 1.0
    val enhancedPromptUsed: String, // The enhanced prompt that was actually sent to the model
    val telemetryUploaded: Boolean = false
)

@Dao
interface ChatDao {
    @Insert
    suspend fun insertMessage(message: ChatMessage)

    @Query("SELECT * FROM chat_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(limit: Int): List<ChatMessage>

    @Query("SELECT * FROM chat_history ORDER BY timestamp ASC")
    suspend fun getAllMessages(): List<ChatMessage>

    @Query("SELECT * FROM chat_history ORDER BY timestamp DESC LIMIT :count")
    suspend fun getLastNMessages(count: Int): List<ChatMessage>

    @Query("DELETE FROM chat_history WHERE id = :messageId")
    suspend fun deleteMessage(messageId: Int)

    @Query("DELETE FROM chat_history")
    suspend fun deleteAllMessages()

    @Query("SELECT COUNT(*) FROM chat_history")
    suspend fun getMessageCount(): Int

    @Query("UPDATE chat_history SET telemetryUploaded = 1 WHERE id = :messageId")
    suspend fun markTelemetryUploaded(messageId: Int)

    @Query("SELECT * FROM chat_history WHERE telemetryUploaded = 0")
    suspend fun getUnuploadedMessages(): List<ChatMessage>
}

@Database(entities = [ChatMessage::class], version = 1, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: ChatDatabase? = null

        fun getInstance(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "arcle_chat_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
