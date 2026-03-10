package com.arcle.intelligence.ui

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity

/**
 * Video Result Activity — full-screen video player for generated videos.
 */
class VideoResultActivity : ComponentActivity() {

    companion object {
        const val EXTRA_VIDEO_URI = "video_uri"
        const val EXTRA_VIDEO_PROMPT = "video_prompt"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#0A0A0C")
        window.navigationBarColor = android.graphics.Color.parseColor("#0A0A0C")

        val videoUriStr = intent.getStringExtra(EXTRA_VIDEO_URI)
        val prompt = intent.getStringExtra(EXTRA_VIDEO_PROMPT) ?: "Generated Video"

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0C"))
        }

        // Video player
        val videoView = VideoView(this).apply {
            if (videoUriStr != null) {
                setVideoURI(Uri.parse(videoUriStr))
            }
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { topMargin = 80; bottomMargin = 160 }
            layoutParams = params

            setOnPreparedListener { mp ->
                mp.isLooping = true
                start()
            }
        }
        rootLayout.addView(videoView)

        // Title
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#CC0A0A0C"))
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
        titleBar.addView(backBtn)

        val titleText = TextView(this).apply {
            text = prompt
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            maxLines = 2
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = 16 }
            layoutParams = params
        }
        titleBar.addView(titleText)
        rootLayout.addView(titleBar)

        // Bottom controls
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

        val shareBtn = Button(this).apply {
            text = "📤 Share Video"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#6E56CF"))
            setPadding(48, 20, 48, 20)
            setOnClickListener {
                if (videoUriStr != null) {
                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "video/*"
                        putExtra(android.content.Intent.EXTRA_STREAM, Uri.parse(videoUriStr))
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(android.content.Intent.createChooser(shareIntent, "Share Video"))
                }
            }
        }
        bottomBar.addView(shareBtn)

        rootLayout.addView(bottomBar)
        setContentView(rootLayout)
    }
}
