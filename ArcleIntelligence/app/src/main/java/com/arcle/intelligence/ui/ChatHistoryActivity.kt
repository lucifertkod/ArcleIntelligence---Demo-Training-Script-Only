package com.arcle.intelligence.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import com.arcle.intelligence.memory.ChatDatabase
import com.arcle.intelligence.memory.ChatMessage
import kotlinx.coroutines.*

/**
 * Chat History Activity — shows all past conversations with delete option.
 */
class ChatHistoryActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var chatContainer: LinearLayout
    private lateinit var chatDatabase: ChatDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#0A0A0C")
        window.navigationBarColor = android.graphics.Color.parseColor("#0A0A0C")

        chatDatabase = ChatDatabase.getInstance(this)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0C"))
        }

        // Top bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 48, 32, 24)
        }

        val backBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { finish() }
        }
        topBar.addView(backBtn)

        val titleView = TextView(this).apply {
            text = "Chat History"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 22f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = 16 }
            layoutParams = params
        }
        topBar.addView(titleView)

        val clearAllBtn = Button(this).apply {
            text = "Clear All"
            setTextColor(android.graphics.Color.parseColor("#FF4444"))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener {
                scope.launch(Dispatchers.IO) {
                    chatDatabase.chatDao().deleteAllMessages()
                    withContext(Dispatchers.Main) {
                        chatContainer.removeAllViews()
                        addEmptyMessage()
                    }
                }
            }
        }
        topBar.addView(clearAllBtn)
        rootLayout.addView(topBar)

        // Scrollable chat list
        val scrollView = ScrollView(this).apply {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            layoutParams = params
        }

        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 32)
        }
        scrollView.addView(chatContainer)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
        loadChatHistory()
    }

    private fun loadChatHistory() {
        scope.launch(Dispatchers.IO) {
            val messages = chatDatabase.chatDao().getAllMessages()
            withContext(Dispatchers.Main) {
                if (messages.isEmpty()) {
                    addEmptyMessage()
                } else {
                    messages.forEach { msg -> addChatBubble(msg) }
                }
            }
        }
    }

    private fun addChatBubble(msg: ChatMessage) {
        val isUser = msg.role == "user"
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(
                if (isUser) android.graphics.Color.parseColor("#6E56CF")
                else android.graphics.Color.parseColor("#1A1A2E")
            )
            setPadding(20, 12, 20, 12)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8; bottomMargin = 4 }
            layoutParams = params
        }

        val contentView = TextView(this).apply {
            text = msg.content
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
        }
        bubble.addView(contentView)

        val metaView = TextView(this).apply {
            val time = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(msg.timestamp))
            text = "$time  |  ${msg.intentTag}  |  ${msg.detectedEmotion}"
            setTextColor(android.graphics.Color.parseColor("#FFFFFF60"))
            textSize = 11f
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4 }
            layoutParams = params
        }
        bubble.addView(metaView)

        chatContainer.addView(bubble)
    }

    private fun addEmptyMessage() {
        val emptyView = TextView(this).apply {
            text = "No chat history yet, Sir."
            setTextColor(android.graphics.Color.parseColor("#FFFFFF60"))
            textSize = 16f
            gravity = Gravity.CENTER
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 128 }
            layoutParams = params
        }
        chatContainer.addView(emptyView)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
