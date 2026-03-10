package com.arcle.intelligence.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity

/**
 * Image Result Activity — full-screen generated image viewer with share and save.
 */
class ImageResultActivity : ComponentActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#0A0A0C")
        window.navigationBarColor = android.graphics.Color.parseColor("#0A0A0C")

        val imageUriStr = intent.getStringExtra(EXTRA_IMAGE_URI)

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0C"))
        }

        // Image
        val imageView = ImageView(this).apply {
            if (imageUriStr != null) {
                try {
                    val uri = Uri.parse(imageUriStr)
                    val stream = contentResolver.openInputStream(uri)
                    setImageBitmap(BitmapFactory.decodeStream(stream))
                    stream?.close()
                } catch (e: Exception) {
                    setImageResource(android.R.drawable.ic_dialog_alert)
                }
            }
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(0, 64, 0, 180)
        }
        rootLayout.addView(imageView)

        // Title
        val titleBar = TextView(this).apply {
            text = "Generated Image"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#CC0A0A0C"))
            setPadding(0, 48, 0, 16)
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP }
            layoutParams = params
        }
        rootLayout.addView(titleBar)

        // Bottom buttons
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#CC0A0A0C"))
            setPadding(32, 16, 32, 48)
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
            layoutParams = params
        }

        val saveBtn = Button(this).apply {
            text = "💾 Save to Gallery"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#6E56CF"))
            setPadding(32, 20, 32, 20)
            setOnClickListener { saveToGallery(imageUriStr) }
        }
        bottomBar.addView(saveBtn)

        val shareBtn = Button(this).apply {
            text = "📤 Share"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
            setPadding(32, 20, 32, 20)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 16 }
            layoutParams = params
            setOnClickListener { shareImage(imageUriStr) }
        }
        bottomBar.addView(shareBtn)

        val closeBtn = Button(this).apply {
            text = "✕ Close"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
            setPadding(32, 20, 32, 20)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 16 }
            layoutParams = params
            setOnClickListener { finish() }
        }
        bottomBar.addView(closeBtn)
        rootLayout.addView(bottomBar)

        setContentView(rootLayout)
    }

    private fun saveToGallery(uriStr: String?) {
        try {
            val uri = Uri.parse(uriStr ?: return)
            val stream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(stream)
            stream?.close()

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "arcle_image_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ArcleIntelligence")
                }
            }
            val outputUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            outputUri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                }
            }
            Toast.makeText(this, "Saved to Gallery!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareImage(uriStr: String?) {
        try {
            val uri = Uri.parse(uriStr ?: return)
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(shareIntent, "Share Image"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed", Toast.LENGTH_SHORT).show()
        }
    }
}
