package com.arcle.intelligence.ai

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.arcle.intelligence.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sherpa KWS (Keyword Spotting) Engine.
 * Continuously listens for wake words using the Sherpa-ONNX keyword spotting model.
 * Runs as part of KwsService (foreground service).
 */
class SherpaKwsEngine(private val context: Context) {

    companion object {
        private const val TAG = "SherpaKwsEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
    }

    interface WakeWordListener {
        fun onWakeWordDetected(keyword: String)
    }

    private var isInitialized = false
    private var isListening = false
    private var listener: WakeWordListener? = null

    // Native model handle — loaded via Sherpa-ONNX JNI bridge
    private var nativeHandle: Long = 0L

    fun setListener(listener: WakeWordListener) {
        this.listener = listener
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            val modelPath = Constants.MODEL_SHERPA_KWS_PATH
            val assetManager = context.assets

            // Copy model files from assets to internal storage for native access
            val modelDir = copyAssetsToInternal(assetManager, modelPath, "sherpa_kws")

            // Initialize the Sherpa-ONNX KWS recognizer
            nativeHandle = initNativeKws(
                encoderPath = "$modelDir/encoder.onnx",
                decoderPath = "$modelDir/decoder.onnx",
                joinerPath = "$modelDir/joiner.onnx",
                tokensPath = "$modelDir/tokens",
                keywordsPath = "$modelDir/keywords",
                sampleRate = SAMPLE_RATE,
                keywords = Constants.WAKE_WORDS.joinToString(",")
            )

            isInitialized = nativeHandle != 0L
            Log.i(TAG, "KWS Engine initialized: $isInitialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize KWS engine", e)
            isInitialized = false
        }
    }

    fun startListening() {
        if (!isInitialized) {
            Log.w(TAG, "Cannot start listening — engine not initialized")
            return
        }
        isListening = true
        Log.i(TAG, "KWS listening started")
    }

    fun stopListening() {
        isListening = false
        Log.i(TAG, "KWS listening stopped")
    }

    /**
     * Feed audio samples from the microphone to the KWS engine.
     * Called by KwsService's audio recording loop.
     */
    fun feedAudioSamples(samples: FloatArray): String? {
        if (!isListening || !isInitialized) return null

        val detectedKeyword = processAudioNative(nativeHandle, samples)
        if (detectedKeyword != null && detectedKeyword.isNotEmpty()) {
            Log.i(TAG, "Wake word detected: $detectedKeyword")
            listener?.onWakeWordDetected(detectedKeyword)
            return detectedKeyword
        }
        return null
    }

    fun release() {
        stopListening()
        if (nativeHandle != 0L) {
            releaseNative(nativeHandle)
            nativeHandle = 0L
        }
        isInitialized = false
    }

    // ── Native JNI bridge methods ────────────────────────────────────────────
    // These call into the Sherpa-ONNX native library (.so)
    private fun initNativeKws(
        encoderPath: String,
        decoderPath: String,
        joinerPath: String,
        tokensPath: String,
        keywordsPath: String,
        sampleRate: Int,
        keywords: String
    ): Long {
        return try {
            System.loadLibrary("sherpa-onnx-jni")
            nativeInitKws(encoderPath, decoderPath, joinerPath, tokensPath, keywordsPath, sampleRate, keywords)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library not found. Ensure sherpa-onnx-jni.so is in jniLibs.", e)
            0L
        }
    }

    private fun processAudioNative(handle: Long, samples: FloatArray): String? {
        return try {
            nativeProcessAudio(handle, samples)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio", e)
            null
        }
    }

    private fun releaseNative(handle: Long) {
        try {
            nativeRelease(handle)
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing native resources", e)
        }
    }

    // JNI external declarations
    private external fun nativeInitKws(
        encoderPath: String, decoderPath: String, joinerPath: String,
        tokensPath: String, keywordsPath: String, sampleRate: Int, keywords: String
    ): Long

    private external fun nativeProcessAudio(handle: Long, samples: FloatArray): String?
    private external fun nativeRelease(handle: Long)

    // ── Asset copying utility ────────────────────────────────────────────────
    private fun copyAssetsToInternal(assetManager: AssetManager, assetPath: String, targetDir: String): String {
        val targetBase = context.filesDir.resolve(targetDir)
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
                    // It's a directory — recurse
                    copyAssetsToInternal(assetManager, fullAssetPath, "$targetDir/$file")
                } else {
                    // It's a file — copy
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
