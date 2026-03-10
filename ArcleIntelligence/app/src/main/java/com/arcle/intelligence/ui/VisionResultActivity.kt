package com.arcle.intelligence.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity

/**
 * Vision Result Activity — displays OCR text, bounding boxes, or analysis results.
 */
class VisionResultActivity : ComponentActivity() {

    companion object {
        const val EXTRA_RESULT_TEXT = "result_text"
        const val EXTRA_IMAGE_URI = "image_uri"
        const val EXTRA_INTENT_TAG = "intent_tag"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#0A0A0C")
        window.navigationBarColor = android.graphics.Color.parseColor("#0A0A0C")

        val resultText = intent.getStringExtra(EXTRA_RESULT_TEXT) ?: "No results available."
        val imageUriStr = intent.getStringExtra(EXTRA_IMAGE_URI)
        val intentTag = intent.getStringExtra(EXTRA_INTENT_TAG) ?: ""

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0C"))
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        // Title
        val title = TextView(this).apply {
            text = when (intentTag) {
                "[VISION_OCR]" -> "Text Extracted"
                "[VISION_DOC]" -> "Document Parsed"
                "[VISION_CODE]" -> "Code Generated"
                "[VISION_MATH]" -> "Solution"
                "[VISION_LOCATE]" -> "Objects Located"
                else -> "Analysis Result"
            }
            setTextColor(android.graphics.Color.WHITE)
            textSize = 24f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        }
        layout.addView(title)

        // Image preview if available
        if (imageUriStr != null) {
            try {
                val uri = Uri.parse(imageUriStr)
                val inputStream = contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                val imageView = ImageView(this).apply {
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 24; bottomMargin = 24 }
                    layoutParams = params
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                layout.addView(imageView)
            } catch (e: Exception) { /* skip image */ }
        }

        // Result text
        val resultView = TextView(this).apply {
            text = resultText
            setTextColor(android.graphics.Color.parseColor("#FFFFFFDD"))
            textSize = 16f
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
            setPadding(24, 24, 24, 24)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            layoutParams = params
            setTextIsSelectable(true)
        }
        layout.addView(resultView)

        // Copy button
        val copyBtn = Button(this).apply {
            text = "Copy to Clipboard"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#6E56CF"))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
            layoutParams = params
            setPadding(0, 24, 0, 24)
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("arcle_result", resultText))
                Toast.makeText(this@VisionResultActivity, "Copied!", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(copyBtn)

        // Back button
        val backBtn = Button(this).apply {
            text = "Back"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
            layoutParams = params
            setPadding(0, 24, 0, 24)
            setOnClickListener { finish() }
        }
        layout.addView(backBtn)

        scrollView.addView(layout)
        setContentView(scrollView)
    }
}
