package com.arcle.intelligence.emotion

import com.arcle.intelligence.utils.Constants

enum class EmotionType {
    HAPPY, SAD, STRESSED, ANXIOUS, ANGRY, EXCITED, NEUTRAL
}

enum class EmotionTrend {
    IMPROVING, WORSENING, STABLE
}

data class EmotionalContext(
    val currentEmotion: EmotionType = EmotionType.NEUTRAL,
    val confidence: Float = 0.0f,
    val trend: EmotionTrend = EmotionTrend.STABLE,
    val recentEmotions: List<EmotionType> = emptyList()
)

data class AudioFeatures(
    val averagePitch: Float = 200f,
    val speakingRate: Float = 1.0f,
    val volumeVariance: Float = 0.3f,
    val pauseFrequency: Float = 0.2f
)

class EmotionalStateEngine {

    private val emotionHistory = mutableListOf<EmotionType>()
    private var messageCounter = 0
    var currentEmotionalContext = EmotionalContext()
        private set

    private val angryKeywords = listOf(
        "stupid", "useless", "terrible", "hate", "awful",
        "broken", "not working", "worst", "horrible", "idiot", "!!", "??!"
    )
    private val sadKeywords = listOf(
        "sad", "depressed", "lonely", "miss", "unhappy",
        "tired", "exhausted", "hopeless", "crying", "hurt", "pain"
    )
    private val stressedKeywords = listOf(
        "stressed", "anxious", "worried", "pressure",
        "deadline", "urgent", "hurry", "quick", "fast", "rush", "asap"
    )
    private val excitedKeywords = listOf(
        "amazing", "awesome", "great", "love", "excited",
        "fantastic", "brilliant", "perfect", "wow", "yes!", "finally"
    )
    private val happyKeywords = listOf(
        "happy", "good", "nice", "thank", "thanks",
        "pleased", "glad", "wonderful", "lovely", "appreciate"
    )

    fun analyzeTextEmotion(userText: String): Pair<EmotionType, Float> {
        val textLower = userText.lowercase()

        val angryScore = angryKeywords.count { textLower.contains(it) }.toFloat()
        val sadScore = sadKeywords.count { textLower.contains(it) }.toFloat()
        val stressedScore = stressedKeywords.count { textLower.contains(it) }.toFloat()
        val excitedScore = excitedKeywords.count { textLower.contains(it) }.toFloat()
        val happyScore = happyKeywords.count { textLower.contains(it) }.toFloat()

        val scores = mapOf(
            EmotionType.ANGRY to angryScore,
            EmotionType.SAD to sadScore,
            EmotionType.STRESSED to stressedScore,
            EmotionType.EXCITED to excitedScore,
            EmotionType.HAPPY to happyScore
        )

        val maxEntry = scores.maxByOrNull { it.value }
        val totalKeywords = scores.values.sum()

        return if (maxEntry != null && maxEntry.value > 0) {
            val confidence = (maxEntry.value / (totalKeywords + 1f)).coerceIn(0.3f, 0.95f)
            Pair(maxEntry.key, confidence)
        } else {
            Pair(EmotionType.NEUTRAL, 0.5f)
        }
    }

    fun analyzeVoiceEmotion(audioFeatures: AudioFeatures): Pair<EmotionType, Float> {
        return when {
            audioFeatures.averagePitch > 300 && audioFeatures.speakingRate > 1.4f ->
                Pair(EmotionType.EXCITED, 0.7f)
            audioFeatures.averagePitch > 280 && audioFeatures.volumeVariance > 0.6f ->
                Pair(EmotionType.ANGRY, 0.75f)
            audioFeatures.speakingRate < 0.8f && audioFeatures.averagePitch < 180 ->
                Pair(EmotionType.SAD, 0.65f)
            audioFeatures.speakingRate > 1.3f && audioFeatures.pauseFrequency > 0.4f ->
                Pair(EmotionType.STRESSED, 0.7f)
            else ->
                Pair(EmotionType.NEUTRAL, 0.5f)
        }
    }

    fun combineEmotionAnalysis(
        textResult: Pair<EmotionType, Float>,
        voiceResult: Pair<EmotionType, Float>? = null
    ): Pair<EmotionType, Float> {
        if (voiceResult == null) return textResult

        // Weight text analysis 0.4, voice analysis 0.6 (voice is more reliable for emotion)
        return if (voiceResult.second > textResult.second) {
            Pair(voiceResult.first, (voiceResult.second * 0.6f + textResult.second * 0.4f))
        } else {
            Pair(textResult.first, (textResult.second * 0.6f + voiceResult.second * 0.4f))
        }
    }

    fun updateEmotionalContext(newEmotion: EmotionType, confidence: Float): EmotionalContext {
        messageCounter++
        emotionHistory.add(newEmotion)
        if (emotionHistory.size > Constants.EMOTION_HISTORY_SIZE) {
            emotionHistory.removeFirst()
        }

        val trend = when {
            isImprovingTrend(emotionHistory) -> EmotionTrend.IMPROVING
            isWorseningTrend(emotionHistory) -> EmotionTrend.WORSENING
            else -> EmotionTrend.STABLE
        }

        currentEmotionalContext = EmotionalContext(
            currentEmotion = newEmotion,
            confidence = confidence,
            trend = trend,
            recentEmotions = emotionHistory.toList()
        )

        return currentEmotionalContext
    }

    fun processUserInput(
        userText: String,
        audioFeatures: AudioFeatures? = null
    ): EmotionalContext {
        val textResult = analyzeTextEmotion(userText)
        val voiceResult = audioFeatures?.let { analyzeVoiceEmotion(it) }
        val combined = combineEmotionAnalysis(textResult, voiceResult)
        return updateEmotionalContext(combined.first, combined.second)
    }

    fun buildEmotionalContextBlock(): String {
        val ctx = currentEmotionalContext
        return """
[EMOTIONAL_CONTEXT]
Current emotion: ${ctx.currentEmotion}
Confidence: ${String.format("%.2f", ctx.confidence)}
Trend: ${ctx.trend}
Adapt your entire response — tone, vocabulary, pacing, and content — to this emotional state.
        """.trimIndent()
    }

    private fun isImprovingTrend(history: List<EmotionType>): Boolean {
        if (history.size < 3) return false
        val positiveEmotions = setOf(EmotionType.HAPPY, EmotionType.EXCITED, EmotionType.NEUTRAL)
        val recentPositive = history.takeLast(3).count { it in positiveEmotions }
        val olderPositive = history.take((history.size - 3).coerceAtLeast(1)).count { it in positiveEmotions }
        return recentPositive > olderPositive
    }

    private fun isWorseningTrend(history: List<EmotionType>): Boolean {
        if (history.size < 3) return false
        val negativeEmotions = setOf(EmotionType.SAD, EmotionType.ANGRY, EmotionType.STRESSED, EmotionType.ANXIOUS)
        val recentNegative = history.takeLast(3).count { it in negativeEmotions }
        val olderNegative = history.take((history.size - 3).coerceAtLeast(1)).count { it in negativeEmotions }
        return recentNegative > olderNegative
    }

    fun shouldReEvaluate(): Boolean {
        return messageCounter % Constants.EMOTION_UPDATE_EVERY_N_MSGS == 0
    }

    fun reset() {
        emotionHistory.clear()
        messageCounter = 0
        currentEmotionalContext = EmotionalContext()
    }
}
