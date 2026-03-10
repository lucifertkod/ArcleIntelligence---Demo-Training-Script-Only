package com.arcle.intelligence.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Vision Input Activity — Camera preview + gallery picker for all VISION_* intents.
 */
class VisionInputActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INTENT_TAG = "intent_tag"
        const val EXTRA_USER_PROMPT = "user_prompt"
        const val RESULT_IMAGE_URI = "result_image_uri"
    }

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var capturedBitmap: Bitmap? = null

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val inputStream: InputStream? = contentResolver.openInputStream(it)
            capturedBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            if (capturedBitmap != null) {
                proceedWithImage(it)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#0A0A0C")
        window.navigationBarColor = android.graphics.Color.parseColor("#0A0A0C")

        cameraExecutor = Executors.newSingleThreadExecutor()

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0C"))
        }

        // Camera preview
        val previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(previewView)

        // Top bar with intent tag
        val intentTag = intent.getStringExtra(EXTRA_INTENT_TAG) ?: "[VISION_UNDERSTAND]"
        val topBar = TextView(this).apply {
            text = when (intentTag) {
                "[VISION_OCR]" -> "📝 Text Recognition"
                "[VISION_DOC]" -> "📄 Document Parser"
                "[VISION_CODE]" -> "💻 Code from Design"
                "[VISION_MATH]" -> "🔢 Math Solver"
                "[VISION_LOCATE]" -> "🎯 Object Locator"
                else -> "👁 Visual Analysis"
            }
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#80000000"))
            setPadding(0, 48, 0, 24)
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP }
            layoutParams = params
        }
        rootLayout.addView(topBar)

        // Bottom controls
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#CC0A0A0C"))
            setPadding(32, 24, 32, 48)
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
            layoutParams = params
        }

        // Gallery button
        val galleryBtn = Button(this).apply {
            text = "Gallery"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
            setPadding(48, 24, 48, 24)
            setOnClickListener { galleryLauncher.launch("image/*") }
        }
        bottomBar.addView(galleryBtn)

        // Capture button
        val captureBtn = Button(this).apply {
            text = "Capture"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#6E56CF"))
            setPadding(64, 24, 64, 24)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 32; marginEnd = 32 }
            layoutParams = params
            setOnClickListener { takePhoto() }
        }
        bottomBar.addView(captureBtn)

        // Cancel button
        val cancelBtn = Button(this).apply {
            text = "Cancel"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
            setPadding(48, 24, 48, 24)
            setOnClickListener { finish() }
        }
        bottomBar.addView(cancelBtn)

        rootLayout.addView(bottomBar)
        setContentView(rootLayout)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera(previewView)
        }
    }

    private fun startCamera(previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                // Camera binding failed
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val outputFile = java.io.File(cacheDir, "captured_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = Uri.fromFile(outputFile)
                    proceedWithImage(uri)
                }
                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(this@VisionInputActivity, "Capture failed", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun proceedWithImage(uri: Uri) {
        val resultIntent = Intent().apply {
            putExtra(RESULT_IMAGE_URI, uri.toString())
            putExtra(EXTRA_INTENT_TAG, intent.getStringExtra(EXTRA_INTENT_TAG))
            putExtra(EXTRA_USER_PROMPT, intent.getStringExtra(EXTRA_USER_PROMPT))
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
