package com.arcle.intelligence.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity

/**
 * Deep Research Activity — live progress screen showing research in action.
 * Shows sites visited counter, scrolling URL list, and final compiled report.
 */
class DeepResearchActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TOPIC = "research_topic"
        const val EXTRA_REPORT = "research_report"
        const val EXTRA_SITES_SEARCHED = "sites_searched"
    }

    private lateinit var progressText: TextView
    private lateinit var urlListContainer: LinearLayout
    private lateinit var reportView: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#0A0A0C")
        window.navigationBarColor = android.graphics.Color.parseColor("#0A0A0C")

        val topic = intent.getStringExtra(EXTRA_TOPIC) ?: "Research"
        val report = intent.getStringExtra(EXTRA_REPORT)
        val sitesSearched = intent.getIntExtra(EXTRA_SITES_SEARCHED, 0)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0C"))
        }

        // Top bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 48, 32, 16)
        }

        val backBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { finish() }
        }
        topBar.addView(backBtn)

        val titleView = TextView(this).apply {
            text = "🔬 Deep Research"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 22f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = 16 }
            layoutParams = params
        }
        topBar.addView(titleView)
        rootLayout.addView(topBar)

        // Topic
        val topicView = TextView(this).apply {
            text = "Topic: $topic"
            setTextColor(android.graphics.Color.parseColor("#6E56CF"))
            textSize = 16f
            setPadding(32, 8, 32, 8)
        }
        rootLayout.addView(topicView)

        // Progress bar
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = if (report != null) 100 else 0
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8; marginStart = 32; marginEnd = 32 }
            layoutParams = params
        }
        rootLayout.addView(progressBar)

        // Progress text
        progressText = TextView(this).apply {
            text = if (report != null) "Complete — $sitesSearched sources analyzed"
                   else "Deep Research in Progress..."
            setTextColor(android.graphics.Color.parseColor("#FFFFFFCC"))
            textSize = 14f
            setPadding(32, 8, 32, 16)
        }
        rootLayout.addView(progressText)

        // Scrollable content
        val scrollView = ScrollView(this).apply {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            layoutParams = params
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 32)
        }

        if (report != null) {
            // Show final report
            reportView = TextView(this).apply {
                text = report
                setTextColor(android.graphics.Color.parseColor("#FFFFFFDD"))
                textSize = 15f
                setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
                setPadding(24, 24, 24, 24)
                setTextIsSelectable(true)
            }
            contentLayout.addView(reportView)
        } else {
            // Show URL list (live updates)
            urlListContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val statusMsg = TextView(this).apply {
                text = "Searching and extracting data from multiple sources..."
                setTextColor(android.graphics.Color.parseColor("#FFFFFF80"))
                textSize = 14f
            }
            urlListContainer.addView(statusMsg)
            contentLayout.addView(urlListContainer)
        }

        scrollView.addView(contentLayout)
        rootLayout.addView(scrollView)

        // Bottom buttons (only if report is ready)
        if (report != null) {
            val bottomBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(32, 16, 32, 48)
            }

            val shareBtn = Button(this).apply {
                text = "📤 Share Report"
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#6E56CF"))
                setPadding(48, 20, 48, 20)
                setOnClickListener {
                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, report)
                    }
                    startActivity(android.content.Intent.createChooser(shareIntent, "Share Report"))
                }
            }
            bottomBar.addView(shareBtn)

            val copyBtn = Button(this).apply {
                text = "📋 Copy"
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
                setPadding(48, 20, 48, 20)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 16 }
                layoutParams = params
                setOnClickListener {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("research_report", report))
                    Toast.makeText(this@DeepResearchActivity, "Report copied!", Toast.LENGTH_SHORT).show()
                }
            }
            bottomBar.addView(copyBtn)

            rootLayout.addView(bottomBar)
        }

        setContentView(rootLayout)
    }

    fun updateProgress(sitesSearched: Int, totalSites: Int, currentUrl: String) {
        runOnUiThread {
            progressText.text = "Searching source $sitesSearched of $totalSites..."
            progressBar.progress = (sitesSearched * 100) / totalSites.coerceAtLeast(1)

            if (::urlListContainer.isInitialized) {
                val urlItem = TextView(this).apply {
                    text = "✓ $currentUrl"
                    setTextColor(android.graphics.Color.parseColor("#FFFFFF80"))
                    textSize = 12f
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 4 }
                    layoutParams = params
                }
                urlListContainer.addView(urlItem)
            }
        }
    }
}
