package com.arcle.intelligence.ai

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.arcle.intelligence.emotion.EmotionType
import com.arcle.intelligence.emotion.EmotionalContext
import com.arcle.intelligence.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sherpa TTS (Text-to-Speech) Engine.
 * Converts text responses to natural speech using the Sherpa-ONNX TTS model.
 * Adapts speaking speed, tone emphasis, and pause patterns based on emotional context.
 */
class SherpaTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "SherpaTtsEngine"
        private const val SAMPLE_RATE = 22050
    }

    interface TtsListener {
        fun onSpeakingStarted()
        fun onSpeakingFinished()
        fun onError(error: String)
    }

    private var isInitialized = false
    private var isSpeaking = false
    private var listener: TtsListener? = null
    private var nativeHandle: Long = 0L
    private var audioTrack: AudioTrack? = null

    fun setListener(listener: TtsListener) {
        this.listener = listener
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            val modelPath = Constants.MODEL_SHERPA_TTS_PATH
            val assetManager = context.assets
            val modelDir = copyAssetsToInternal(assetManager, modelPath, "sherpa_tts")

            nativeHandle = initNativeTts(
                modelPath = "$modelDir/model.onnx",
                tokensPath = "$modelDir/tokens",
                dataDirPath = "$modelDir/espeak-ng-data",
                sampleRate = SAMPLE_RATE
            )

            isInitialized = nativeHandle != 0L
            Log.i(TAG, "TTS Engine initialized: $isInitialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TTS engine", e)
            isInitialized = false
        }
    }

    /**
     * Speak text with emotional adaptation.
     * Adapts speed, pitch, and pausing based on the user's current emotional state.
     */
    suspend fun speak(text: String, emotionalContext: EmotionalContext) = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            listener?.onError("TTS engine not initialized")
            return@withContext
        }

        // Wait for previous speech to finish
        if (isSpeaking) {
            stopSpeaking()
        }

        try {
            isSpeaking = true
            listener?.onSpeakingStarted()

            // Determine speaking parameters based on emotion
            val (speed, pitch) = getEmotionSpeechParams(emotionalContext.currentEmotion)

            // Generate audio samples from text
            val audioSamples = synthesizeSpeech(nativeHandle, text, speed, pitch)

            if (audioSamples != null && audioSamples.isNotEmpty()) {
                playAudio(audioSamples)
            }

            isSpeaking = false
            listener?.onSpeakingFinished()
        } catch (e: Exception) {
            Log.e(TAG, "Error during TTS", e)
            isSpeaking = false
            listener?.onError("Speech synthesis failed")
        }
    }

    /**
     * Get speaking parameters adapted to the user's emotional state.
     * Returns Pair(speed, pitch) where:
     * - speed: 0.5 (slow) to 2.0 (fast), default 1.0
     * - pitch: 0.5 (low) to 2.0 (high), default 1.0
     */
    private fun getEmotionSpeechParams(emotion: EmotionType): Pair<Float, Float> {
        return when (emotion) {
            EmotionType.SAD -> Pair(0.85f, 0.9f)          // Slower, softer
            EmotionType.STRESSED, EmotionType.ANXIOUS -> Pair(0.9f, 0.95f)  // Calm, measured
            EmotionType.ANGRY -> Pair(0.9f, 1.0f)         // Calm, steady
            EmotionType.EXCITED -> Pair(1.15f, 1.1f)      // Faster, more energetic
            EmotionType.HAPPY -> Pair(1.1f, 1.05f)        // Slightly upbeat
            EmotionType.NEUTRAL -> Pair(1.0f, 1.0f)       // Standard delivery
        }
    }

    private fun playAudio(samples: FloatArray) {
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize.coerceAtLeast(samples.size * 4))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack?.let { track ->
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()

            // Wait for playback to finish
            val durationMs = (samples.size.toLong() * 1000) / SAMPLE_RATE
            Thread.sleep(durationMs + 100) // Small buffer

            track.stop()
            track.release()
        }
        audioTrack = null
    }

    fun stopSpeaking() {
        isSpeaking = false
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio", e)
        }
    }

    fun isBusy(): Boolean = isSpeaking

    fun release() {
        stopSpeaking()
        if (nativeHandle != 0L) {
            releaseNative(nativeHandle)
            nativeHandle = 0L
        }
        isInitialized = false
    }

    // ── Native JNI bridge methods ────────────────────────────────────────────
    private fun initNativeTts(
        modelPath: String, tokensPath: String,
        dataDirPath: String, sampleRate: Int
    ): Long {
        return try {
            System.loadLibrary("sherpa-onnx-jni")
            nativeInitTts(modelPath, tokensPath, dataDirPath, sampleRate)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library not found", e)
            0L
        }
    }

    private fun synthesizeSpeech(handle: Long, text: String, speed: Float, pitch: Float): FloatArray? {
        return try {
            nativeSynthesizeSpeech(handle, text, speed, pitch)
        } catch (e: Exception) {
            Log.e(TAG, "Error synthesizing speech", e)
            null
        }
    }

    private fun releaseNative(handle: Long) {
        try {
            nativeReleaseTts(handle)
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing TTS", e)
        }
    }

    // JNI external declarations
    private external fun nativeInitTts(
        modelPath: String, tokensPath: String,
        dataDirPath: String, sampleRate: Int
    ): Long
    private external fun nativeSynthesizeSpeech(
        handle: Long, text: String, speed: Float, pitch: Float
    ): FloatArray?
    private external fun nativeReleaseTts(handle: Long)

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
