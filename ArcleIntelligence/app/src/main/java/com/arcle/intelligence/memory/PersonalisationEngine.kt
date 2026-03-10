package com.arcle.intelligence.memory

import android.content.Context
import androidx.room.*
import com.arcle.intelligence.emotion.EmotionType
import com.arcle.intelligence.emotion.EmotionalStateEngine

/**
 * Personalisation Engine.
 * Extracts and stores user preferences locally over time.
 * Injects top preferences + emotional pattern into system prompt.
 */

@Entity(tableName = "user_preferences")
data class UserPreference(
    @PrimaryKey val key: String,
    val value: String,
    val weight: Float,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Dao
interface PreferenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreference(pref: UserPreference)

    @Query("SELECT * FROM user_preferences ORDER BY weight DESC LIMIT :limit")
    suspend fun getTopPreferences(limit: Int): List<UserPreference>

    @Query("SELECT * FROM user_preferences WHERE key = :key")
    suspend fun getPreference(key: String): UserPreference?

    @Query("SELECT * FROM user_preferences ORDER BY weight DESC")
    suspend fun getAllPreferences(): List<UserPreference>

    @Query("DELETE FROM user_preferences WHERE key = :key")
    suspend fun deletePreference(key: String)
}

@Database(entities = [UserPreference::class], version = 1, exportSchema = false)
abstract class PreferenceDatabase : RoomDatabase() {
    abstract fun preferenceDao(): PreferenceDao

    companion object {
        @Volatile
        private var INSTANCE: PreferenceDatabase? = null

        fun getInstance(context: Context): PreferenceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PreferenceDatabase::class.java,
                    "arcle_preference_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class PersonalisationEngine(private val context: Context) {

    companion object {
        private const val TAG = "PersonalisationEngine"
        private const val PREF_LIKED_TOPICS = "liked_topics"
        private const val PREF_DISLIKED_TOPICS = "disliked_topics"
        private const val PREF_FREQUENT_APPS = "frequent_apps"
        private const val PREF_FREQUENT_INTENTS = "frequent_intents"
    }

    private val dao = PreferenceDatabase.getInstance(context).preferenceDao()
    private val intentCounts = mutableMapOf<String, Int>()
    private val appCounts = mutableMapOf<String, Int>()

    /**
     * Record a user interaction to learn preferences.
     */
    suspend fun recordInteraction(
        intentTag: String,
        userInput: String,
        appUsed: String? = null
    ) {
        // Track frequent intents
        intentCounts[intentTag] = (intentCounts[intentTag] ?: 0) + 1
        val intentValue = intentCounts.entries.sortedByDescending { it.value }
            .take(5).joinToString(", ") { it.key }
        dao.upsertPreference(UserPreference(PREF_FREQUENT_INTENTS, intentValue, intentCounts.values.sum().toFloat()))

        // Track frequent apps
        if (appUsed != null) {
            appCounts[appUsed] = (appCounts[appUsed] ?: 0) + 1
            val appValue = appCounts.entries.sortedByDescending { it.value }
                .take(5).joinToString(", ") { it.key }
            dao.upsertPreference(UserPreference(PREF_FREQUENT_APPS, appValue, appCounts.values.sum().toFloat()))
        }

        // Extract liked topics from creative/chat interactions
        if (intentTag in listOf("[CHAT]", "[CREATIVE]", "[DEEP_RESEARCH]")) {
            extractTopics(userInput)
        }
    }

    private suspend fun extractTopics(input: String) {
        val topicKeywords = listOf(
            "cricket", "football", "basketball", "soccer", "sports",
            "technology", "tech", "programming", "coding", "ai", "science",
            "music", "movies", "games", "gaming", "anime", "manga",
            "news", "politics", "history", "math", "physics", "chemistry",
            "cooking", "food", "travel", "photography", "art", "design",
            "fitness", "health", "meditation", "yoga", "books", "reading"
        )

        val inputLower = input.lowercase()
        val foundTopics = topicKeywords.filter { inputLower.contains(it) }

        if (foundTopics.isNotEmpty()) {
            val existingPref = dao.getPreference(PREF_LIKED_TOPICS)
            val existingTopics = existingPref?.value?.split(", ")?.toMutableSet() ?: mutableSetOf()
            existingTopics.addAll(foundTopics)
            val weight = existingTopics.size.toFloat()
            dao.upsertPreference(UserPreference(PREF_LIKED_TOPICS, existingTopics.joinToString(", "), weight))
        }
    }

    /**
     * Build the user preferences block to inject into the system prompt.
     */
    suspend fun buildPreferencesBlock(emotionalEngine: EmotionalStateEngine): String {
        val topPrefs = dao.getTopPreferences(10)
        if (topPrefs.isEmpty()) return ""

        val prefBuilder = StringBuilder("[USER PREFERENCES]\n")
        for (pref in topPrefs) {
            val label = when (pref.key) {
                PREF_LIKED_TOPICS -> "- Likes"
                PREF_DISLIKED_TOPICS -> "- Dislikes"
                PREF_FREQUENT_APPS -> "- Frequently uses"
                PREF_FREQUENT_INTENTS -> "- Common requests"
                else -> "- ${pref.key}"
            }
            prefBuilder.append("$label: [${pref.value}]\n")
        }

        // Add emotional pattern
        val recentEmotions = emotionalEngine.currentEmotionalContext.recentEmotions
        if (recentEmotions.isNotEmpty()) {
            val dominantEmotion = recentEmotions.groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key ?: EmotionType.NEUTRAL
            prefBuilder.append("- Current emotional tendency: [usually ${dominantEmotion.name.lowercase()}]\n")
        }

        return prefBuilder.toString()
    }
}
