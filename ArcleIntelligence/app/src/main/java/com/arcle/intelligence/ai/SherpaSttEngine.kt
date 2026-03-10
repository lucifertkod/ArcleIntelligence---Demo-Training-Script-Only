package com.arcle.intelligence.ai

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.arcle.intelligence.emotion.AudioFeatures
import com.arcle.intelligence.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Sherpa STT (Speech-to-Text) Engine.
 * Converts spoken audio into text using the Sherpa-ONNX streaming recognition model.
 * Activated after wake word detection or manual trigger.
 */
class SherpaSttEngine(private val context: Context) {

    companion object {
        private const val TAG = "SherpaSttEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val SILENCE_THRESHOLD = 500
        private const val SILENCE_DURATION_MS = 1500L
    }

    interface SttListener {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String, audioFeatures: AudioFeatures)
        fun onError(error: String)
        fun onListeningStarted()
        fun onListeningEnded()
    }

    private var isInitialized = false
    private var isRecording = false
    private var listener: SttListener? = null
    private var audioRecord: AudioRecord? = null
    private var nativeHandle: Long = 0L

    fun setListener(listener: SttListener) {
        this.listener = listener
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            val modelPath = Constants.MODEL_SHERPA_STT_PATH
            val assetManager = context.assets
            val modelDir = copyAssetsToInternal(assetManager, modelPath, "sherpa_stt")

            nativeHandle = initNativeStt(
                encoderPath = "$modelDir/encoder.onnx",
                decoderPath = "$modelDir/decoder.onnx",
                joinerPath = "$modelDir/joiner.onnx",
                tokensPath = "$modelDir/tokens",
                sampleRate = SAMPLE_RATE
            )

            isInitialized = nativeHandle != 0L
            Log.i(TAG, "STT Engine initialized: $isInitialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize STT engine", e)
            isInitialized = false
        }
    }

    suspend fun startRecognition() = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            listener?.onError("STT engine not initialized")
            return@withContext
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )
        } catch (e: SecurityException) {
            listener?.onError("Microphone permission not granted")
            return@withContext
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            listener?.onError("Failed to initialize audio recorder")
            return@withContext
        }

        isRecording = true
        audioRecord?.startRecording()
        listener?.onListeningStarted()

        val buffer = ShortArray(bufferSize / 2)
        val audioSamples = mutableListOf<Float>()
        var silenceStartTime = 0L
        var totalAmplitude = 0.0
        var sampleCount = 0
        var maxAmplitude = 0f
        var zeroCrossings = 0
        var previousSample = 0f

        // Audio feature accumulators
        val amplitudes = mutableListOf<Float>()
        val pitchEstimates = mutableListOf<Float>()

        resetNativeStream(nativeHandle)

        while (isRecording) {
            val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            if (readCount <= 0) continue

            // Convert shorts to floats
            val floatBuffer = FloatArray(readCount) { buffer[it].toFloat() / Short.MAX_VALUE }
            audioSamples.addAll(floatBuffer.toList())

            // Calculate audio features
            var frameAmplitude = 0f
            for (i in 0 until readCount) {
                val sample = floatBuffer[i]
                frameAmplitude += abs(sample)

                // Zero-crossing rate for pitch estimation
                if (previousSample * sample < 0) {
                    zeroCrossings++
                }
                previousSample = sample
            }
            frameAmplitude /= readCount
            amplitudes.add(frameAmplitude)

            totalAmplitude += frameAmplitude
            sampleCount++
            if (frameAmplitude > maxAmplitude) maxAmplitude = frameAmplitude

            // Estimate pitch from zero-crossing rate
            val zcr = zeroCrossings.toFloat() / readCount * SAMPLE_RATE / 2f
            pitchEstimates.add(zcr)
            zeroCrossings = 0

            // Feed to native STT
            val partialText = feedAudioToStt(nativeHandle, floatBuffer)
            if (partialText != null && partialText.isNotEmpty()) {
                listener?.onPartialResult(partialText)
            }

            // Silence detection
            val rms = sqrt(floatBuffer.map { it * it }.average().toFloat())
            if (rms * Short.MAX_VALUE < SILENCE_THRESHOLD) {
                if (silenceStartTime == 0L) {
                    silenceStartTime = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - silenceStartTime > SILENCE_DURATION_MS) {
                    // Silence detected — finalize
                    break
                }
            } else {
                silenceStartTime = 0L
            }
        }

        // Finalize recognition
        val finalText = finalizeRecognition(nativeHandle)
        stopRecording()

        // Build audio features for emotional analysis
        val avgAmplitude = if (amplitudes.isNotEmpty()) amplitudes.average().toFloat() else 0f
        val avgPitch = if (pitchEstimates.isNotEmpty()) pitchEstimates.average().toFloat() else 200f
        val speakingRate = if (audioSamples.isNotEmpty()) {
            val durationSeconds = audioSamples.size.toFloat() / SAMPLE_RATE
            val wordCount = finalText?.split(" ")?.size ?: 0
            if (durationSeconds > 0) wordCount / durationSeconds / 2.5f else 1.0f
        } else 1.0f

        val volumeVariance = if (amplitudes.size > 1) {
            val mean = amplitudes.average()
            sqrt(amplitudes.map { (it - mean) * (it - mean) }.average()).toFloat()
        } else 0.3f

        val pauseFrequency = if (amplitudes.isNotEmpty()) {
            amplitudes.count { it < 0.01f }.toFloat() / amplitudes.size
        } else 0.2f

        val audioFeatures = AudioFeatures(
            averagePitch = avgPitch,
            speakingRate = speakingRate.coerceIn(0.3f, 3.0f),
            volumeVariance = volumeVariance,
            pauseFrequency = pauseFrequency
        )

        if (finalText != null && finalText.isNotEmpty()) {
            listener?.onFinalResult(finalText, audioFeatures)
        } else {
            listener?.onError("Could not recognize speech")
        }

        listener?.onListeningEnded()
    }

    fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio record", e)
        }
        audioRecord = null
    }

    fun release() {
        stopRecording()
        if (nativeHandle != 0L) {
            releaseNative(nativeHandle)
            nativeHandle = 0L
        }
        isInitialized = false
    }

    // ── Native JNI bridge methods ────────────────────────────────────────────
    private fun initNativeStt(
        encoderPath: String, decoderPath: String,
        joinerPath: String, tokensPath: String, sampleRate: Int
    ): Long {
        return try {
            System.loadLibrary("sherpa-onnx-jni")
            nativeInitStt(encoderPath, decoderPath, joinerPath, tokensPath, sampleRate)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library not found", e)
            0L
        }
    }

    private fun feedAudioToStt(handle: Long, samples: FloatArray): String? {
        return try {
            nativeFeedAudio(handle, samples)
        } catch (e: Exception) {
            null
        }
    }

    private fun finalizeRecognition(handle: Long): String? {
        return try {
            nativeFinalize(handle)
        } catch (e: Exception) {
            null
        }
    }

    private fun resetNativeStream(handle: Long) {
        try {
            nativeResetStream(handle)
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting stream", e)
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
    private external fun nativeInitStt(
        encoderPath: String, decoderPath: String,
        joinerPath: String, tokensPath: String, sampleRate: Int
    ): Long
    private external fun nativeFeedAudio(handle: Long, samples: FloatArray): String?
    private external fun nativeFinalize(handle: Long): String?
    private external fun nativeResetStream(handle: Long)
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
