package com.arcle.intelligence.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import java.io.File

/**
 * Code Output Activity — displays file tree for generated code projects + share button.
 */
class CodeOutputActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PROJECT_PATH = "project_path"
        const val EXTRA_PROJECT_NAME = "project_name"
        const val EXTRA_FILE_COUNT = "file_count"
        const val EXTRA_LINE_COUNT = "line_count"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#0A0A0C")
        window.navigationBarColor = android.graphics.Color.parseColor("#0A0A0C")

        val projectPath = intent.getStringExtra(EXTRA_PROJECT_PATH) ?: ""
        val projectName = intent.getStringExtra(EXTRA_PROJECT_NAME) ?: "Project"
        val fileCount = intent.getIntExtra(EXTRA_FILE_COUNT, 0)
        val lineCount = intent.getIntExtra(EXTRA_LINE_COUNT, 0)

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
            text = "💻 $projectName"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 22f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = 16 }
            layoutParams = params
        }
        topBar.addView(titleView)
        rootLayout.addView(topBar)

        // Stats
        val statsView = TextView(this).apply {
            text = "$fileCount files  •  $lineCount lines  •  Ready to run"
            setTextColor(android.graphics.Color.parseColor("#6E56CF"))
            textSize = 14f
            setPadding(32, 8, 32, 16)
        }
        rootLayout.addView(statsView)

        // File tree
        val scrollView = ScrollView(this).apply {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            layoutParams = params
        }

        val treeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 32)
        }

        if (projectPath.isNotEmpty()) {
            val projectDir = File(projectPath)
            if (projectDir.exists()) {
                buildFileTree(treeContainer, projectDir, 0)
            } else {
                val noFiles = TextView(this).apply {
                    text = "Project directory not found."
                    setTextColor(android.graphics.Color.parseColor("#FFFFFF60"))
                    textSize = 14f
                }
                treeContainer.addView(noFiles)
            }
        }

        scrollView.addView(treeContainer)
        rootLayout.addView(scrollView)

        // Bottom buttons
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(32, 16, 32, 48)
        }

        val shareBtn = Button(this).apply {
            text = "📤 Share Folder"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#6E56CF"))
            setPadding(48, 20, 48, 20)
            setOnClickListener {
                Toast.makeText(this@CodeOutputActivity, "Files at: $projectPath", Toast.LENGTH_LONG).show()
            }
        }
        bottomBar.addView(shareBtn)

        rootLayout.addView(bottomBar)
        setContentView(rootLayout)
    }

    private fun buildFileTree(container: LinearLayout, dir: File, depth: Int) {
        val items = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: return

        for (item in items) {
            val indent = "    ".repeat(depth)
            val icon = if (item.isDirectory) "📁" else getFileIcon(item.name)
            val size = if (item.isFile) " (${formatSize(item.length())})" else ""

            val itemView = TextView(this).apply {
                text = "$indent$icon ${item.name}$size"
                setTextColor(
                    if (item.isDirectory) android.graphics.Color.parseColor("#6E56CF")
                    else android.graphics.Color.parseColor("#FFFFFFCC")
                )
                textSize = 14f
                setPadding(0, 6, 0, 6)

                if (item.isFile) {
                    setOnClickListener {
                        // Show file content preview
                        showFilePreview(item)
                    }
                }
            }
            container.addView(itemView)

            if (item.isDirectory) {
                buildFileTree(container, item, depth + 1)
            }
        }
    }

    private fun getFileIcon(name: String): String {
        return when {
            name.endsWith(".kt") -> "🟣"
            name.endsWith(".java") -> "☕"
            name.endsWith(".html") -> "🌐"
            name.endsWith(".css") -> "🎨"
            name.endsWith(".js") -> "⚡"
            name.endsWith(".py") -> "🐍"
            name.endsWith(".json") -> "📋"
            name.endsWith(".xml") -> "📄"
            name.endsWith(".md") -> "📝"
            else -> "📄"
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> "${bytes / (1024 * 1024)}MB"
        }
    }

    private fun showFilePreview(file: File) {
        try {
            val content = file.readText().take(2000)
            val dialog = android.app.AlertDialog.Builder(this)
                .setTitle(file.name)
                .setMessage(content)
                .setPositiveButton("Close") { d, _ -> d.dismiss() }
                .create()
            dialog.show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not read file", Toast.LENGTH_SHORT).show()
        }
    }
}
