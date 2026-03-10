package com.arcle.intelligence.ai

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.arcle.intelligence.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * YOLO 11n Object Detection Engine.
 * Detects objects in real-time using the YOLO 11n ONNX model.
 * Provides bounding boxes with labels and confidence scores.
 */
class Yolo11nEngine(private val context: Context) {

    companion object {
        private const val TAG = "Yolo11nEngine"
        private const val INPUT_SIZE = 640

        // COCO 80 class labels
        val COCO_LABELS = arrayOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
            "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
            "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
        )
    }

    data class DetectedObject(
        val label: String,
        val confidence: Float,
        val boundingBox: RectF,
        val classId: Int
    )

    private var isInitialized = false
    private var nativeHandle: Long = 0L

    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            val modelPath = Constants.MODEL_YOLO_PATH
            val assetManager = context.assets
            val modelDir = copyAssetsToInternal(assetManager, modelPath, "yolo11n")

            nativeHandle = initNativeYolo(
                modelPath = "$modelDir/yolo11n_640.onnx",
                inputSize = INPUT_SIZE,
                confidenceThreshold = Constants.YOLO_CONFIDENCE_THRESHOLD
            )

            isInitialized = nativeHandle != 0L
            Log.i(TAG, "YOLO Engine initialized: $isInitialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize YOLO engine", e)
            isInitialized = false
        }
    }

    /**
     * Detect objects in a bitmap image.
     * Returns list of detected objects with bounding boxes, labels, and confidence scores.
     */
    suspend fun detectObjects(bitmap: Bitmap): List<DetectedObject> = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.e(TAG, "YOLO engine not initialized")
            return@withContext emptyList()
        }

        try {
            // Step 1: Resize bitmap to model input size
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

            // Step 2: Convert bitmap to float array (CHW format, normalized 0-1)
            val inputArray = bitmapToFloatArray(resizedBitmap)

            // Step 3: Run inference
            val rawOutput = runDetection(nativeHandle, inputArray)
            if (rawOutput == null) {
                Log.e(TAG, "Detection returned null")
                return@withContext emptyList()
            }

            // Step 4: Parse raw output into DetectedObject list
            val detections = parseDetections(rawOutput, bitmap.width.toFloat(), bitmap.height.toFloat())

            // Step 5: Apply Non-Maximum Suppression (NMS)
            val nmsResults = applyNMS(detections, 0.45f)

            Log.i(TAG, "Detected ${nmsResults.size} objects")
            nmsResults
        } catch (e: Exception) {
            Log.e(TAG, "Error during detection", e)
            emptyList()
        }
    }

    /**
     * Convert Bitmap to float array in CHW format (channels, height, width), normalized 0-1.
     */
    private fun bitmapToFloatArray(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val floatArray = FloatArray(3 * width * height)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            floatArray[i] = ((pixel shr 16) and 0xFF) / 255f              // R
            floatArray[i + width * height] = ((pixel shr 8) and 0xFF) / 255f  // G
            floatArray[i + 2 * width * height] = (pixel and 0xFF) / 255f      // B
        }
        return floatArray
    }

    /**
     * Parse raw YOLO output into DetectedObject list.
     * Output format: [batch, num_detections, 85] where 85 = 4 (bbox) + 1 (obj_conf) + 80 (class_probs)
     */
    private fun parseDetections(
        rawOutput: FloatArray,
        origWidth: Float,
        origHeight: Float
    ): List<DetectedObject> {
        val detections = mutableListOf<DetectedObject>()
        val numClasses = COCO_LABELS.size
        val stride = 4 + 1 + numClasses // cx, cy, w, h, obj_conf, class_probs...
        val numDetections = rawOutput.size / stride

        for (i in 0 until numDetections) {
            val offset = i * stride
            if (offset + stride > rawOutput.size) break

            val objConf = rawOutput[offset + 4]
            if (objConf < Constants.YOLO_CONFIDENCE_THRESHOLD) continue

            // Find best class
            var bestClassId = 0
            var bestClassProb = 0f
            for (c in 0 until numClasses) {
                val prob = rawOutput[offset + 5 + c]
                if (prob > bestClassProb) {
                    bestClassProb = prob
                    bestClassId = c
                }
            }

            val finalConfidence = objConf * bestClassProb
            if (finalConfidence < Constants.YOLO_CONFIDENCE_THRESHOLD) continue

            // Convert from center format to corner format and scale to original image
            val cx = rawOutput[offset] * origWidth / INPUT_SIZE
            val cy = rawOutput[offset + 1] * origHeight / INPUT_SIZE
            val w = rawOutput[offset + 2] * origWidth / INPUT_SIZE
            val h = rawOutput[offset + 3] * origHeight / INPUT_SIZE

            val bbox = RectF(
                (cx - w / 2).coerceAtLeast(0f),
                (cy - h / 2).coerceAtLeast(0f),
                (cx + w / 2).coerceAtMost(origWidth),
                (cy + h / 2).coerceAtMost(origHeight)
            )

            detections.add(
                DetectedObject(
                    label = if (bestClassId < COCO_LABELS.size) COCO_LABELS[bestClassId] else "unknown",
                    confidence = finalConfidence,
                    boundingBox = bbox,
                    classId = bestClassId
                )
            )
        }

        return detections
    }

    /**
     * Non-Maximum Suppression to remove overlapping detections.
     */
    private fun applyNMS(detections: List<DetectedObject>, iouThreshold: Float): List<DetectedObject> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val result = mutableListOf<DetectedObject>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeFirst()
            result.add(best)

            sorted.removeAll { detection ->
                if (detection.classId == best.classId) {
                    calculateIoU(best.boundingBox, detection.boundingBox) > iouThreshold
                } else {
                    false
                }
            }
        }

        return result
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val intersectionLeft = maxOf(a.left, b.left)
        val intersectionTop = maxOf(a.top, b.top)
        val intersectionRight = minOf(a.right, b.right)
        val intersectionBottom = minOf(a.bottom, b.bottom)

        val intersectionWidth = (intersectionRight - intersectionLeft).coerceAtLeast(0f)
        val intersectionHeight = (intersectionBottom - intersectionTop).coerceAtLeast(0f)
        val intersectionArea = intersectionWidth * intersectionHeight

        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val unionArea = areaA + areaB - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0f
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
    private fun initNativeYolo(modelPath: String, inputSize: Int, confidenceThreshold: Float): Long {
        return try {
            System.loadLibrary("onnxruntime")
            nativeInitYolo(modelPath, inputSize, confidenceThreshold)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "ONNX Runtime library not found", e)
            0L
        }
    }

    private fun runDetection(handle: Long, input: FloatArray): FloatArray? {
        return try { nativeRunDetection(handle, input) } catch (e: Exception) { null }
    }

    private fun releaseNative(handle: Long) {
        try { nativeReleaseYolo(handle) } catch (e: Exception) { }
    }

    // JNI external declarations
    private external fun nativeInitYolo(modelPath: String, inputSize: Int, threshold: Float): Long
    private external fun nativeRunDetection(handle: Long, input: FloatArray): FloatArray?
    private external fun nativeReleaseYolo(handle: Long)

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
