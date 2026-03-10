package com.arcle.intelligence.ai

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import com.arcle.intelligence.enhancement.PromptEnhancementEngine
import com.arcle.intelligence.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SDXS 512 Text-to-Image Engine.
 * Generates images from text prompts using the SDXS ONNX model.
 * All prompts are automatically enhanced before inference.
 */
class SdxsEngine(private val context: Context) {

    companion object {
        private const val TAG = "SdxsEngine"
        private const val IMAGE_WIDTH = 512
        private const val IMAGE_HEIGHT = 512
    }

    private var isInitialized = false
    private var nativeHandle: Long = 0L

    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            val modelPath = Constants.MODEL_SDXS_PATH
            val assetManager = context.assets
            val modelDir = copyAssetsToInternal(assetManager, modelPath, "sdxs512")

            nativeHandle = initNativeSdxs(
                textEncoderPath = "$modelDir/text encoder model.onnx",
                unetPath = "$modelDir/Unet model.onnx",
                vocabPath = "$modelDir/vocab",
                mergesPath = "$modelDir/merges",
                schedulerConfigPath = "$modelDir/scheduler_config"
            )

            isInitialized = nativeHandle != 0L
            Log.i(TAG, "SDXS Engine initialized: $isInitialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SDXS engine", e)
            isInitialized = false
        }
    }

    /**
     * Generate an image from a text prompt.
     * Always passes through PromptEnhancementEngine before inference.
     */
    suspend fun generateImage(rawPrompt: String): Bitmap? = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.e(TAG, "SDXS engine not initialized")
            return@withContext null
        }

        try {
            // Step 1: Enhance the prompt
            val enhancedPrompt = PromptEnhancementEngine.enhance(rawPrompt, "[IMAGE]")
            Log.i(TAG, "Enhanced prompt: $enhancedPrompt")

            // Step 2: Run text encoding
            val textEmbedding = encodeText(nativeHandle, enhancedPrompt)
            if (textEmbedding == null) {
                Log.e(TAG, "Text encoding failed")
                return@withContext null
            }

            // Step 3: Run diffusion (UNet inference)
            val latentOutput = runDiffusion(nativeHandle, textEmbedding)
            if (latentOutput == null) {
                Log.e(TAG, "Diffusion failed")
                return@withContext null
            }

            // Step 4: Decode latent to bitmap
            val bitmap = decodeToBitmap(latentOutput, IMAGE_WIDTH, IMAGE_HEIGHT)

            Log.i(TAG, "Image generated successfully: ${bitmap?.width}x${bitmap?.height}")
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error generating image", e)
            null
        }
    }

    /**
     * Convert raw latent float array to a Bitmap.
     */
    private fun decodeToBitmap(latent: FloatArray, width: Int, height: Int): Bitmap? {
        return try {
            val pixels = IntArray(width * height)
            for (i in 0 until width * height) {
                val r = ((latent[i] + 1f) / 2f * 255f).toInt().coerceIn(0, 255)
                val g = ((latent[i + width * height] + 1f) / 2f * 255f).toInt().coerceIn(0, 255)
                val b = ((latent[i + 2 * width * height] + 1f) / 2f * 255f).toInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding bitmap", e)
            null
        }
    }

    fun isReady(): Boolean = isInitialized

    fun release() {
        if (nativeHandle != 0L) {
            releaseNative(nativeHandle)
            nativeHandle = 0L
        }
        isInitialized = false
    }

    // ── Native JNI bridge methods ────────────────────────────────────────────
    private fun initNativeSdxs(
        textEncoderPath: String, unetPath: String,
        vocabPath: String, mergesPath: String, schedulerConfigPath: String
    ): Long {
        return try {
            System.loadLibrary("onnxruntime")
            nativeInitSdxs(textEncoderPath, unetPath, vocabPath, mergesPath, schedulerConfigPath)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "ONNX Runtime library not found", e)
            0L
        }
    }

    private fun encodeText(handle: Long, prompt: String): FloatArray? {
        return try {
            nativeEncodeText(handle, prompt)
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding text", e)
            null
        }
    }

    private fun runDiffusion(handle: Long, textEmbedding: FloatArray): FloatArray? {
        return try {
            nativeRunDiffusion(handle, textEmbedding)
        } catch (e: Exception) {
            Log.e(TAG, "Error running diffusion", e)
            null
        }
    }

    private fun releaseNative(handle: Long) {
        try {
            nativeReleaseSdxs(handle)
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing SDXS", e)
        }
    }

    // JNI external declarations
    private external fun nativeInitSdxs(
        textEncoderPath: String, unetPath: String,
        vocabPath: String, mergesPath: String, schedulerConfigPath: String
    ): Long
    private external fun nativeEncodeText(handle: Long, prompt: String): FloatArray?
    private external fun nativeRunDiffusion(handle: Long, textEmbedding: FloatArray): FloatArray?
    private external fun nativeReleaseSdxs(handle: Long)

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
