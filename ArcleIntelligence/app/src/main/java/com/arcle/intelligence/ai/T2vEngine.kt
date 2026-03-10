package com.arcle.intelligence.ai

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.arcle.intelligence.enhancement.PromptEnhancementEngine
import com.arcle.intelligence.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Text-to-Video Engine (MobileT2V).
 * Generates short video clips from text prompts using the custom MobileT2V model.
 * All prompts are automatically enhanced before inference.
 */
class T2vEngine(private val context: Context) {

    companion object {
        private const val TAG = "T2vEngine"
        private const val VIDEO_WIDTH = 256
        private const val VIDEO_HEIGHT = 256
        private const val VIDEO_FPS = 8
        private const val VIDEO_FRAMES = 16
    }

    private var isInitialized = false
    private var nativeHandle: Long = 0L

    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            val modelPath = Constants.MODEL_T2V_PATH
            val assetManager = context.assets
            val modelDir = copyAssetsToInternal(assetManager, modelPath, "mobile_t2v")

            nativeHandle = initNativeT2v(
                clipModelPath = "$modelDir/mobileclip_text.onnx",
                clipModelInt8Path = "$modelDir/mobileclip_text_int8.onnx",
                decoderPath = "$modelDir/arcle_decoder_int8.onnx",
                metadataPath = "$modelDir/arcle_model_metadata",
                vocabPath = "$modelDir/clip_vocab",
                mergesPath = "$modelDir/clip_bpe_merges",
                videoIndexPath = "$modelDir/video_index",
                videoLatentsPath = "$modelDir/video_latents",
                videoTextIndexPath = "$modelDir/video_text_index"
            )

            isInitialized = nativeHandle != 0L
            Log.i(TAG, "T2V Engine initialized: $isInitialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize T2V engine", e)
            isInitialized = false
        }
    }

    /**
     * Generate a video from a text prompt.
     * Always passes through PromptEnhancementEngine before inference.
     * Returns a File pointing to the generated MP4.
     */
    suspend fun generateVideo(rawPrompt: String): File? = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.e(TAG, "T2V engine not initialized")
            return@withContext null
        }

        try {
            // Step 1: Enhance the prompt
            val enhancedPrompt = PromptEnhancementEngine.enhance(rawPrompt, "[VIDEO]")
            Log.i(TAG, "Enhanced video prompt: $enhancedPrompt")

            // Step 2: Encode text with CLIP
            val textEmbedding = encodeText(nativeHandle, enhancedPrompt)
            if (textEmbedding == null) {
                Log.e(TAG, "Text encoding failed")
                return@withContext null
            }

            // Step 3: Retrieve closest video latents
            val videoLatents = retrieveVideoLatents(nativeHandle, textEmbedding)
            if (videoLatents == null) {
                Log.e(TAG, "Video latent retrieval failed")
                return@withContext null
            }

            // Step 4: Decode latents to frames
            val frameData = decodeLatentsToFrames(nativeHandle, videoLatents, textEmbedding)
            if (frameData == null) {
                Log.e(TAG, "Frame decoding failed")
                return@withContext null
            }

            // Step 5: Encode frames to MP4 file
            val outputFile = File(context.cacheDir, "arcle_video_${System.currentTimeMillis()}.mp4")
            val success = encodeFramesToMp4(frameData, outputFile, VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)

            if (success) {
                Log.i(TAG, "Video generated: ${outputFile.absolutePath}")
                outputFile
            } else {
                Log.e(TAG, "MP4 encoding failed")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating video", e)
            null
        }
    }

    /**
     * Encode raw frame data to MP4 using Android's MediaCodec/MediaMuxer.
     */
    private fun encodeFramesToMp4(
        frameData: FloatArray,
        outputFile: File,
        width: Int,
        height: Int,
        fps: Int
    ): Boolean {
        return try {
            val pixelsPerFrame = width * height * 3
            val numFrames = frameData.size / pixelsPerFrame

            // Use Android MediaCodec for hardware-accelerated encoding
            val format = android.media.MediaFormat.createVideoFormat(
                android.media.MediaFormat.MIMETYPE_VIDEO_AVC,
                width, height
            ).apply {
                setInteger(android.media.MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(android.media.MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(
                    android.media.MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
            }

            val encoder = android.media.MediaCodec.createEncoderByType(
                android.media.MediaFormat.MIMETYPE_VIDEO_AVC
            )
            encoder.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val muxer = android.media.MediaMuxer(
                outputFile.absolutePath,
                android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            var trackIndex = -1
            var muxerStarted = false
            val bufferInfo = android.media.MediaCodec.BufferInfo()

            for (frameIndex in 0 until numFrames.coerceAtMost(VIDEO_FRAMES)) {
                // Get input buffer
                val inputIndex = encoder.dequeueInputBuffer(10000)
                if (inputIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputIndex) ?: continue
                    inputBuffer.clear()

                    // Convert float RGB to NV21/YUV
                    val offset = frameIndex * pixelsPerFrame
                    val yuvData = rgbFloatToYuv(frameData, offset, width, height)
                    inputBuffer.put(yuvData)

                    val pts = (frameIndex * 1_000_000L / fps)
                    encoder.queueInputBuffer(inputIndex, 0, yuvData.size, pts, 0)
                }

                // Drain output
                var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                while (outputIndex >= 0) {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null) {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                }
            }

            // Signal end of stream
            val inputIndex = encoder.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(
                    inputIndex, 0, 0, 0,
                    android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }

            // Drain remaining output
            var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
            while (outputIndex >= 0) {
                val outputBuffer = encoder.getOutputBuffer(outputIndex)
                if (outputBuffer != null && muxerStarted) {
                    muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                }
                encoder.releaseOutputBuffer(outputIndex, false)
                if (bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
            }

            encoder.stop()
            encoder.release()
            if (muxerStarted) {
                muxer.stop()
                muxer.release()
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding MP4", e)
            false
        }
    }

    private fun rgbFloatToYuv(data: FloatArray, offset: Int, width: Int, height: Int): ByteArray {
        val yuvSize = width * height * 3 / 2
        val yuv = ByteArray(yuvSize)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val safeOffset = offset.coerceAtMost(data.size - width * height * 3)
                val r = ((data[safeOffset + idx] + 1f) / 2f * 255f).toInt().coerceIn(0, 255)
                val g = ((data[safeOffset + idx + width * height] + 1f) / 2f * 255f).toInt().coerceIn(0, 255)
                val b = ((data[safeOffset + idx + 2 * width * height] + 1f) / 2f * 255f).toInt().coerceIn(0, 255)

                // Y plane
                yuv[idx] = ((0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)).toByte()

                // UV planes (subsampled)
                if (y % 2 == 0 && x % 2 == 0) {
                    val uvIdx = width * height + (y / 2) * width + x
                    if (uvIdx + 1 < yuvSize) {
                        yuv[uvIdx] = (((-0.169 * r - 0.331 * g + 0.5 * b + 128).toInt()).coerceIn(0, 255)).toByte()
                        yuv[uvIdx + 1] = (((0.5 * r - 0.419 * g - 0.081 * b + 128).toInt()).coerceIn(0, 255)).toByte()
                    }
                }
            }
        }
        return yuv
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
    private fun initNativeT2v(
        clipModelPath: String, clipModelInt8Path: String,
        decoderPath: String, metadataPath: String,
        vocabPath: String, mergesPath: String,
        videoIndexPath: String, videoLatentsPath: String,
        videoTextIndexPath: String
    ): Long {
        return try {
            System.loadLibrary("onnxruntime")
            nativeInitT2v(
                clipModelPath, clipModelInt8Path, decoderPath,
                metadataPath, vocabPath, mergesPath,
                videoIndexPath, videoLatentsPath, videoTextIndexPath
            )
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "ONNX Runtime library not found", e)
            0L
        }
    }

    private fun encodeText(handle: Long, prompt: String): FloatArray? {
        return try { nativeEncodeText(handle, prompt) } catch (e: Exception) { null }
    }

    private fun retrieveVideoLatents(handle: Long, textEmb: FloatArray): FloatArray? {
        return try { nativeRetrieveLatents(handle, textEmb) } catch (e: Exception) { null }
    }

    private fun decodeLatentsToFrames(handle: Long, latents: FloatArray, textEmb: FloatArray): FloatArray? {
        return try { nativeDecodeFrames(handle, latents, textEmb) } catch (e: Exception) { null }
    }

    private fun releaseNative(handle: Long) {
        try { nativeReleaseT2v(handle) } catch (e: Exception) { }
    }

    // JNI external declarations
    private external fun nativeInitT2v(
        clipModelPath: String, clipModelInt8Path: String,
        decoderPath: String, metadataPath: String,
        vocabPath: String, mergesPath: String,
        videoIndexPath: String, videoLatentsPath: String,
        videoTextIndexPath: String
    ): Long
    private external fun nativeEncodeText(handle: Long, prompt: String): FloatArray?
    private external fun nativeRetrieveLatents(handle: Long, textEmb: FloatArray): FloatArray?
    private external fun nativeDecodeFrames(handle: Long, latents: FloatArray, textEmb: FloatArray): FloatArray?
    private external fun nativeReleaseT2v(handle: Long)

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
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copying assets from $assetPath", e)
        }
        return targetBase.absolutePath
    }
}
