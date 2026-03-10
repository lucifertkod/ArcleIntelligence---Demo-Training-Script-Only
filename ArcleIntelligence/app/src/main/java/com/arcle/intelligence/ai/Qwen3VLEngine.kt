package com.arcle.intelligence.ai

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import com.arcle.intelligence.emotion.EmotionalContext
import com.arcle.intelligence.enhancement.PromptEnhancementEngine
import com.arcle.intelligence.memory.ChatMessage
import com.arcle.intelligence.utils.ArcleSystemPrompt
import com.arcle.intelligence.utils.BiasDatabase
import com.arcle.intelligence.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Qwen3-VL 2B Vision-Language Model Engine.
 * The primary LLM that handles all text generation, vision understanding, and code generation.
 * Loaded once at app startup and kept in memory.
 */
class Qwen3VLEngine(private val context: Context) {

    companion object {
        private const val TAG = "Qwen3VLEngine"
    }

    private var isInitialized = false
    private var nativeHandle: Long = 0L

    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            val modelPath = Constants.MODEL_QWEN3_VL_PATH
            val assetManager = context.assets
            val modelDir = copyAssetsToInternal(assetManager, modelPath, "qwen3_vl")

            nativeHandle = initNativeLlm(
                modelDir = modelDir,
                maxTokens = Constants.LLM_MAX_TOKENS,
                temperature = Constants.LLM_TEMPERATURE
            )

            isInitialized = nativeHandle != 0L

            // Register AI enhancer callback for PromptEnhancementEngine
            if (isInitialized) {
                PromptEnhancementEngine.aiEnhancer = { prompt ->
                    generateResponseSync(prompt, emptyList(), EmotionalContext())
                }
            }

            Log.i(TAG, "Qwen3-VL Engine initialized: $isInitialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Qwen3-VL engine", e)
            isInitialized = false
        }
    }

    /**
     * Generate a text response from the LLM.
     * Pipeline: BiasCheck → PromptEnhancement (if needed) → SystemPrompt + Context → Inference → StripThinkTags
     */
    suspend fun generateResponse(
        prompt: String,
        context: List<ChatMessage>,
        emotionalContext: EmotionalContext
    ): String = withContext(Dispatchers.IO) {
        // Step 1: Check bias database FIRST — if match found, return directly without calling model
        val biasCorrection = BiasDatabase.getCorrection(prompt)
        if (biasCorrection != null) {
            return@withContext biasCorrection
        }

        if (!isInitialized) {
            return@withContext "I apologize, Sir. My language model is not ready yet. Please wait a moment."
        }

        try {
            // Step 2: Build the emotional context block
            val emotionalBlock = buildEmotionalContextBlock(emotionalContext)

            // Step 3: Build conversation context from chat history
            val conversationContext = buildConversationContext(context)

            // Step 4: Build the full prompt with system prompt
            val fullPrompt = buildString {
                append(ArcleSystemPrompt.SYSTEM_PROMPT)
                append("\n\n")
                append(emotionalBlock)
                append("\n\n")
                append(conversationContext)
                append("\nUser: ")
                append(prompt)
                append("\nAssistant: ")
            }

            // Step 5: Run inference
            val rawResponse = runInference(nativeHandle, fullPrompt)

            // Step 6: Strip <think>...</think> blocks
            val cleanResponse = stripThinkBlocks(rawResponse)

            cleanResponse
        } catch (e: Exception) {
            Log.e(TAG, "Error during LLM inference", e)
            "I encountered an issue processing your request, Sir. Please try again."
        }
    }

    /**
     * Generate a vision response from the LLM (image or video understanding).
     */
    suspend fun generateVisionResponse(
        prompt: String,
        imageBitmap: Bitmap? = null,
        videoFrames: List<Bitmap>? = null,
        context: List<ChatMessage>,
        emotionalContext: EmotionalContext
    ): String = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            return@withContext "I apologize, Sir. My vision model is not ready yet."
        }

        try {
            val emotionalBlock = buildEmotionalContextBlock(emotionalContext)
            val conversationContext = buildConversationContext(context)

            val fullPrompt = buildString {
                append(ArcleSystemPrompt.SYSTEM_PROMPT)
                append("\n\n")
                append(emotionalBlock)
                append("\n\n")
                append(conversationContext)
                append("\nUser: ")
                append(prompt)
                append("\nAssistant: ")
            }

            val rawResponse = if (imageBitmap != null) {
                runVisionInference(nativeHandle, fullPrompt, imageBitmap)
            } else if (videoFrames != null && videoFrames.isNotEmpty()) {
                // For video, process key frames
                val combinedResponse = StringBuilder()
                for ((index, frame) in videoFrames.withIndex()) {
                    val framePrompt = if (index == 0) fullPrompt
                        else "Continue analyzing frame ${index + 1} of the video."
                    val frameResult = runVisionInference(nativeHandle, framePrompt, frame)
                    combinedResponse.append(frameResult).append(" ")
                }
                combinedResponse.toString().trim()
            } else {
                runInference(nativeHandle, fullPrompt)
            }

            stripThinkBlocks(rawResponse)
        } catch (e: Exception) {
            Log.e(TAG, "Error during vision inference", e)
            "I encountered an issue analyzing the visual input, Sir."
        }
    }

    /**
     * Synchronous version for internal use (prompt enhancement).
     */
    fun generateResponseSync(
        prompt: String,
        context: List<ChatMessage>,
        emotionalContext: EmotionalContext
    ): String {
        if (!isInitialized) return prompt

        return try {
            val rawResponse = runInference(nativeHandle, prompt)
            stripThinkBlocks(rawResponse)
        } catch (e: Exception) {
            Log.e(TAG, "Error in sync inference", e)
            prompt
        }
    }

    /**
     * Strip <think>...</think> blocks from the response before passing to TTS.
     */
    private fun stripThinkBlocks(response: String): String {
        return response.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
    }

    private fun buildEmotionalContextBlock(ctx: EmotionalContext): String {
        return """
[EMOTIONAL_CONTEXT]
Current emotion: ${ctx.currentEmotion}
Confidence: ${String.format("%.2f", ctx.confidence)}
Trend: ${ctx.trend}
Adapt your entire response — tone, vocabulary, pacing, and content — to this emotional state.
        """.trimIndent()
    }

    private fun buildConversationContext(messages: List<ChatMessage>): String {
        if (messages.isEmpty()) return ""

        val recentMessages = messages.takeLast(Constants.LLM_CONTEXT_WINDOW)
        return recentMessages.joinToString("\n") { msg ->
            val role = if (msg.role == "user") "User" else "Assistant"
            "$role: ${msg.content}"
        }
    }

    fun isReady(): Boolean = isInitialized

    fun release() {
        if (nativeHandle != 0L) {
            releaseNative(nativeHandle)
            nativeHandle = 0L
        }
        isInitialized = false
        PromptEnhancementEngine.aiEnhancer = null
    }

    // ── Native JNI bridge methods ────────────────────────────────────────────
    private fun initNativeLlm(modelDir: String, maxTokens: Int, temperature: Float): Long {
        return try {
            System.loadLibrary("mnn-llm-jni")
            nativeInitLlm(modelDir, maxTokens, temperature)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native MNN library not found", e)
            0L
        }
    }

    private fun runInference(handle: Long, prompt: String): String {
        return try {
            nativeRunInference(handle, prompt) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Inference error", e)
            ""
        }
    }

    private fun runVisionInference(handle: Long, prompt: String, image: Bitmap): String {
        return try {
            nativeRunVisionInference(handle, prompt, image) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Vision inference error", e)
            ""
        }
    }

    private fun releaseNative(handle: Long) {
        try {
            nativeReleaseLlm(handle)
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing LLM", e)
        }
    }

    // JNI external declarations
    private external fun nativeInitLlm(modelDir: String, maxTokens: Int, temperature: Float): Long
    private external fun nativeRunInference(handle: Long, prompt: String): String?
    private external fun nativeRunVisionInference(handle: Long, prompt: String, image: Bitmap): String?
    private external fun nativeReleaseLlm(handle: Long)

    // ── Asset copying utility ────────────────────────────────────────────────
    private fun copyAssetsToInternal(assetManager: AssetManager, assetPath: String, targetDir: String): String {
        val targetBase = this.context.filesDir.resolve(targetDir)
        if (targetBase.exists() && targetBase.listFiles()?.isNotEmpty() == true) {
            return targetBase.absolutePath
        }
        targetBase.mkdirs()

        try {
            val files = assetManager.list(assetPath) ?: return targetBase.absolutePath
            for (file in files) {
                val fullAssetPath = "$assetPath/$file"
                val subFiles = assetManager.list(fullAssetPath)
                if (subFiles != null && subFiles.isNotEmpty()) {
                    copyAssetsToInternal(assetManager, fullAssetPath, "$targetDir/$file")
                } else {
                    val outFile = targetBase.resolve(file)
                    assetManager.open(fullAssetPath).use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copying assets from $assetPath", e)
        }

        return targetBase.absolutePath
    }
}
