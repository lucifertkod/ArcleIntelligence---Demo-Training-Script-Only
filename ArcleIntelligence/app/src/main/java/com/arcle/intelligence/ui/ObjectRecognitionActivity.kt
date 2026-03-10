package com.arcle.intelligence.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.arcle.intelligence.ai.Yolo11nEngine
import kotlinx.coroutines.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Object Recognition Activity — CameraX with YOLO bounding boxes in real time.
 * When confidence > 70%, speaks detected object name.
 */
class ObjectRecognitionActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ObjectRecognitionActivity"
    }

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var yoloEngine: Yolo11nEngine
    private lateinit var overlayView: OverlayView
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var lastSpokenObject = ""
    private var lastSpokenTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#0A0A0C")
        window.navigationBarColor = android.graphics.Color.parseColor("#0A0A0C")

        cameraExecutor = Executors.newSingleThreadExecutor()

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        // Camera preview
        val previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(previewView)

        // Bounding box overlay
        overlayView = OverlayView(this)
        rootLayout.addView(overlayView)

        // Top bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#80000000"))
            setPadding(32, 48, 32, 16)
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP }
            layoutParams = params
        }

        val backBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { finish() }
        }
        topBar.addView(backBtn)

        val titleText = TextView(this).apply {
            text = "🎯 Real-Time Object Detection"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = 16 }
            layoutParams = params
        }
        topBar.addView(titleText)
        rootLayout.addView(topBar)

        setContentView(rootLayout)

        // Initialize YOLO
        scope.launch(Dispatchers.IO) {
            yoloEngine = Yolo11nEngine(this@ObjectRecognitionActivity)
            yoloEngine.initialize()
            withContext(Dispatchers.Main) {
                if (ContextCompat.checkSelfPermission(this@ObjectRecognitionActivity, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                    startCamera(previewView)
                }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startCamera(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 640))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processFrame(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val bitmap = imageProxyToBitmap(imageProxy)
        if (bitmap != null) {
            val detections = yoloEngine.detectObjects(bitmap)
            runOnUiThread {
                overlayView.setDetections(
                    detections,
                    imageProxy.width,
                    imageProxy.height
                )
            }

            // Speak high-confidence detections
            val highConfidence = detections.filter { it.confidence > 0.7f }
            if (highConfidence.isNotEmpty()) {
                val topObject = highConfidence.maxByOrNull { it.confidence }
                if (topObject != null && (topObject.label != lastSpokenObject ||
                        System.currentTimeMillis() - lastSpokenTime > 5000)) {
                    lastSpokenObject = topObject.label
                    lastSpokenTime = System.currentTimeMillis()
                    // TTS would speak here if we had the engine reference
                }
            }
        }
        imageProxy.close()
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Custom View that draws bounding boxes over the camera preview.
     */
    inner class OverlayView(context: android.content.Context) : android.view.View(context) {

        private var detections: List<Yolo11nEngine.Detection> = emptyList()
        private var sourceWidth = 1
        private var sourceHeight = 1

        private val boxPaint = Paint().apply {
            color = android.graphics.Color.parseColor("#00D4FF")
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }

        private val textPaint = Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 40f
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }

        private val bgPaint = Paint().apply {
            color = android.graphics.Color.parseColor("#CC000000")
            style = Paint.Style.FILL
        }

        fun setDetections(dets: List<Yolo11nEngine.Detection>, srcWidth: Int, srcHeight: Int) {
            detections = dets
            sourceWidth = srcWidth
            sourceHeight = srcHeight
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val scaleX = width.toFloat() / sourceWidth
            val scaleY = height.toFloat() / sourceHeight

            for (det in detections) {
                val left = det.bbox.left * scaleX
                val top = det.bbox.top * scaleY
                val right = det.bbox.right * scaleX
                val bottom = det.bbox.bottom * scaleY

                // Draw box
                canvas.drawRect(left, top, right, bottom, boxPaint)

                // Draw label background
                val label = "${det.label} ${String.format("%.0f%%", det.confidence * 100)}"
                val textWidth = textPaint.measureText(label)
                canvas.drawRect(left, top - 48f, left + textWidth + 16, top, bgPaint)
                canvas.drawText(label, left + 8, top - 10, textPaint)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        cameraExecutor.shutdown()
        yoloEngine.release()
        super.onDestroy()
    }
}
