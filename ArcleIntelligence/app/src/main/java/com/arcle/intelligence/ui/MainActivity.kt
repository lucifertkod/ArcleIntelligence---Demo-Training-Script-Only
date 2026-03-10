package com.arcle.intelligence.ui

import android.content.*
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import com.arcle.intelligence.ArcleApplication
import com.arcle.intelligence.ai.*
import com.arcle.intelligence.automation.*
import com.arcle.intelligence.emotion.AudioFeatures
import com.arcle.intelligence.emotion.EmotionalStateEngine
import com.arcle.intelligence.enhancement.PromptEnhancementEngine
import com.arcle.intelligence.memory.*
import com.arcle.intelligence.research.DeepResearchEngine
import com.arcle.intelligence.services.KwsService
import com.arcle.intelligence.telemetry.TelemetryBatchManager
import com.arcle.intelligence.utils.*
import kotlinx.coroutines.*

/**
 * Main Activity — central orchestrator for the Arcle Intelligence app.
 * Manages all AI engines, intent routing, and the conversation UI.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // AI Engines
    private lateinit var qwen3VLEngine: Qwen3VLEngine
    private lateinit var sherpaSttEngine: SherpaSttEngine
    private lateinit var sherpaTtsEngine: SherpaTtsEngine
    private lateinit var sdxsEngine: SdxsEngine
    private lateinit var t2vEngine: T2vEngine
    private lateinit var yolo11nEngine: Yolo11nEngine

    // Feature Modules
    private lateinit var emotionalEngine: EmotionalStateEngine
    private lateinit var codeGenerationManager: CodeGenerationManager
    private lateinit var deviceControlManager: DeviceControlManager
    private lateinit var internetAppManager: InternetAppManager
    private lateinit var callManager: CallManager
    private lateinit var messageManager: MessageManager
    private lateinit var systemInfoManager: SystemInfoManager
    private lateinit var realTimeDataManager: RealTimeDataManager
    private lateinit var deepResearchEngine: DeepResearchEngine
    private lateinit var personalisationEngine: PersonalisationEngine
    private lateinit var reminderManager: ReminderManager
    private lateinit var telemetryManager: TelemetryBatchManager

    // Databases
    private lateinit var chatDatabase: ChatDatabase

    // UI Elements
    private lateinit var chatContainer: LinearLayout
    private lateinit var inputField: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var micButton: ImageButton
    private lateinit var statusText: TextView
    private lateinit var scrollView: ScrollView

    // State
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val chatHistory = mutableListOf<ChatMessage>()

    // Wake word receiver
    private val wakeWordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == KwsService.ACTION_WAKE_WORD_DETECTED) {
                val keyword = intent.getStringExtra("keyword") ?: ""
                Log.i(TAG, "Wake word received: $keyword")
                startVoiceInput()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.parseColor("#0A0A0C")
        window.navigationBarColor = android.graphics.Color.parseColor("#0A0A0C")

        buildUI()
        initializeEngines()
        registerReceiver(
            wakeWordReceiver,
            IntentFilter(KwsService.ACTION_WAKE_WORD_DETECTED),
            RECEIVER_NOT_EXPORTED
        )

        // Start KWS service
        startForegroundService(Intent(this, KwsService::class.java))

        // Check pending reminders on startup
        if (Constants.REMINDER_CHECK_ON_STARTUP) {
            mainScope.launch {
                val reminderMsg = reminderManager.getPendingRemindersMessage()
                if (reminderMsg != null) {
                    addAssistantMessage(reminderMsg)
                    sherpaTtsEngine.speak(reminderMsg, emotionalEngine.currentEmotionalContext)
                }
            }
        }
    }

    private fun buildUI() {
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0C"))
        }

        // Chat area
        scrollView = ScrollView(this).apply {
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = 180 }
            layoutParams = params
            isFillViewport = true
        }

        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scrollView.addView(chatContainer)
        rootLayout.addView(scrollView)

        // Status text
        statusText = TextView(this).apply {
            text = "Arcle Intelligence — Online"
            setTextColor(android.graphics.Color.parseColor("#6E56CF"))
            textSize = 12f
            gravity = Gravity.CENTER
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8; gravity = Gravity.TOP }
            layoutParams = params
        }
        rootLayout.addView(statusText)

        // Input bar
        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
            setPadding(24, 16, 24, 16)
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
            layoutParams = params
        }

        micButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(16, 16, 16, 16)
            setOnClickListener { startVoiceInput() }
        }
        inputBar.addView(micButton)

        inputField = EditText(this).apply {
            hint = "Type a message..."
            setHintTextColor(android.graphics.Color.parseColor("#FFFFFF40"))
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#FFFFFF12"))
            textSize = 16f
            setSingleLine(false)
            maxLines = 4
            setPadding(24, 16, 24, 16)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = 12; marginEnd = 12 }
            layoutParams = params
        }
        inputBar.addView(inputField)

        sendButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            setBackgroundColor(android.graphics.Color.parseColor("#6E56CF"))
            setPadding(24, 16, 24, 16)
            setOnClickListener { handleSendButton() }
        }
        inputBar.addView(sendButton)

        rootLayout.addView(inputBar)
        setContentView(rootLayout)

        // Welcome message
        addAssistantMessage(ArcleResponses.random(ArcleResponses.GREETINGS))
    }

    private fun initializeEngines() {
        // Initialize all engines
        chatDatabase = ChatDatabase.getInstance(this)
        emotionalEngine = EmotionalStateEngine()
        personalisationEngine = PersonalisationEngine(this)
        reminderManager = ReminderManager(this)
        telemetryManager = (application as ArcleApplication).telemetryManager
        deviceControlManager = DeviceControlManager(this)
        callManager = CallManager(this)
        messageManager = MessageManager(this)
        systemInfoManager = SystemInfoManager(this)
        realTimeDataManager = RealTimeDataManager(this)

        // Initialize AI engines in background
        mainScope.launch(Dispatchers.IO) {
            statusText.post { statusText.text = "Loading AI models..." }

            qwen3VLEngine = Qwen3VLEngine(this@MainActivity)
            qwen3VLEngine.initialize()

            sherpaSttEngine = SherpaSttEngine(this@MainActivity)
            sherpaSttEngine.initialize()

            sherpaTtsEngine = SherpaTtsEngine(this@MainActivity)
            sherpaTtsEngine.initialize()

            sdxsEngine = SdxsEngine(this@MainActivity)
            sdxsEngine.initialize()

            t2vEngine = T2vEngine(this@MainActivity)
            t2vEngine.initialize()

            yolo11nEngine = Yolo11nEngine(this@MainActivity)
            yolo11nEngine.initialize()

            internetAppManager = InternetAppManager(this@MainActivity)
            deepResearchEngine = DeepResearchEngine(internetAppManager)
            codeGenerationManager = CodeGenerationManager(this@MainActivity, qwen3VLEngine)

            statusText.post { statusText.text = "Arcle Intelligence — Ready" }
        }
    }

    private fun handleSendButton() {
        val text = inputField.text.toString().trim()
        if (text.isEmpty()) return

        inputField.text.clear()
        processUserInput(text)
    }

    private fun startVoiceInput() {
        statusText.text = "Listening..."

        sherpaSttEngine.setListener(object : SherpaSttEngine.SttListener {
            override fun onPartialResult(text: String) {
                runOnUiThread { statusText.text = "Heard: $text" }
            }

            override fun onFinalResult(text: String, audioFeatures: AudioFeatures) {
                runOnUiThread {
                    statusText.text = "Arcle Intelligence — Processing"
                    processUserInput(text, audioFeatures)
                }
            }

            override fun onError(error: String) {
                runOnUiThread { statusText.text = "Arcle Intelligence — Ready" }
            }

            override fun onListeningStarted() {
                runOnUiThread { statusText.text = "Listening..." }
            }

            override fun onListeningEnded() {
                runOnUiThread { statusText.text = "Processing..." }
            }
        })

        mainScope.launch(Dispatchers.IO) {
            sherpaSttEngine.startRecognition()
        }
    }

    private fun processUserInput(text: String, audioFeatures: AudioFeatures? = null) {
        addUserMessage(text)
        val startTime = System.currentTimeMillis()

        mainScope.launch {
            try {
                // Step 1: Emotional analysis
                val emotionalContext = emotionalEngine.processUserInput(text, audioFeatures)

                // Step 2: Classify intent
                val classification = IntentClassifier.classify(text)
                val intent = classification.intent

                // Step 3: Route to handler
                val response = when (intent) {
                    IntentClassifier.IntentTag.IDENTITY -> {
                        IdentityResponses.getIdentityResponse(text)
                    }

                    IntentClassifier.IntentTag.AUTO_OFFLINE -> {
                        addAssistantMessage(ArcleResponses.random(ArcleResponses.OFFLINE_PHRASES))
                        val result = deviceControlManager.executeCommand(text)
                        result
                    }

                    IntentClassifier.IntentTag.AUTO_NET -> {
                        val appName = classification.extractedParams["appName"] ?: "app"
                        val packageName = classification.extractedParams["packageName"] ?: ""
                        withContext(Dispatchers.IO) {
                            internetAppManager.executeInternetFlow(appName, packageName)
                        }
                        "Opening $appName for you, Sir."
                    }

                    IntentClassifier.IntentTag.AUTO_CALL -> {
                        val contact = classification.extractedParams["contact"] ?: "unknown"
                        callManager.makeCall(contact)
                    }

                    IntentClassifier.IntentTag.AUTO_MSG -> {
                        val contact = classification.extractedParams["contact"] ?: "unknown"
                        val message = classification.extractedParams["message"] ?: text
                        messageManager.prepareMessage(contact, message, text.contains("whatsapp", ignoreCase = true))
                    }

                    IntentClassifier.IntentTag.AUTO_SYSTEM -> {
                        systemInfoManager.getSystemInfo(text)
                    }

                    IntentClassifier.IntentTag.WEATHER -> {
                        realTimeDataManager.getWeather()
                    }

                    IntentClassifier.IntentTag.REMINDER -> {
                        val task = classification.extractedParams["task"] ?: text
                        val timeStr = classification.extractedParams["time"] ?: ""
                        val triggerTime = reminderManager.parseTimeString(timeStr)
                        val isRepeating = timeStr.startsWith("every")
                        withContext(Dispatchers.IO) {
                            reminderManager.setReminder(task, triggerTime, isRepeating, if (isRepeating) triggerTime else 0)
                        }
                    }

                    IntentClassifier.IntentTag.IMAGE -> {
                        addAssistantMessage(ArcleResponses.random(ArcleResponses.CONFIRMATIONS))
                        withContext(Dispatchers.IO) {
                            val bitmap = sdxsEngine.generateImage(text)
                            if (bitmap != null) {
                                withContext(Dispatchers.Main) { addImageMessage(bitmap) }
                                "Here is the generated image, Sir."
                            } else {
                                "I couldn't generate the image, Sir. My image model may not be ready."
                            }
                        }
                    }

                    IntentClassifier.IntentTag.VIDEO -> {
                        addAssistantMessage(ArcleResponses.random(ArcleResponses.CONFIRMATIONS))
                        withContext(Dispatchers.IO) {
                            val videoFile = t2vEngine.generateVideo(text)
                            if (videoFile != null) {
                                "Video generated and saved to ${videoFile.name}, Sir."
                            } else {
                                "I couldn't generate the video, Sir."
                            }
                        }
                    }

                    IntentClassifier.IntentTag.CODE_APP,
                    IntentClassifier.IntentTag.CODE_WEB,
                    IntentClassifier.IntentTag.CODE_GAME,
                    IntentClassifier.IntentTag.CODE_SCRIPT,
                    IntentClassifier.IntentTag.CODE_API -> {
                        addAssistantMessage(ArcleResponses.random(ArcleResponses.CONFIRMATIONS))
                        withContext(Dispatchers.IO) {
                            val project = codeGenerationManager.generateCode(
                                text, intent.tag, chatHistory, emotionalContext
                            )
                            "Code project '${project.projectName}' generated with ${project.files.size} files " +
                                "(${project.totalLines} lines). Saved to ${project.outputDir.absolutePath}, Sir."
                        }
                    }

                    IntentClassifier.IntentTag.DEEP_RESEARCH -> {
                        addAssistantMessage(ArcleResponses.random(ArcleResponses.DEEP_RESEARCH_START))
                        withContext(Dispatchers.IO) {
                            val result = deepResearchEngine.conductResearch(text)
                            result.reportText
                        }
                    }

                    IntentClassifier.IntentTag.MATH,
                    IntentClassifier.IntentTag.TRANSLATE,
                    IntentClassifier.IntentTag.CREATIVE,
                    IntentClassifier.IntentTag.CHAT -> {
                        withContext(Dispatchers.IO) {
                            qwen3VLEngine.generateResponse(text, chatHistory, emotionalContext)
                        }
                    }

                    IntentClassifier.IntentTag.VISION_UNDERSTAND,
                    IntentClassifier.IntentTag.VISION_VIDEO_UNDERSTAND,
                    IntentClassifier.IntentTag.VISION_OCR,
                    IntentClassifier.IntentTag.VISION_DOC,
                    IntentClassifier.IntentTag.VISION_CODE,
                    IntentClassifier.IntentTag.VISION_MATH,
                    IntentClassifier.IntentTag.VISION_LOCATE -> {
                        withContext(Dispatchers.IO) {
                            qwen3VLEngine.generateVisionResponse(
                                text, null, null, chatHistory, emotionalContext
                            )
                        }
                    }
                }

                addAssistantMessage(response)

                // Speak response
                launch(Dispatchers.IO) {
                    sherpaTtsEngine.speak(response, emotionalContext)
                }

                // Save to chat history
                val responseTimeMs = System.currentTimeMillis() - startTime
                val chatMsg = ChatMessage(
                    role = "user", content = text,
                    timestamp = startTime,
                    intentTag = intent.tag,
                    detectedEmotion = emotionalContext.currentEmotion.name,
                    emotionConfidence = emotionalContext.confidence,
                    enhancedPromptUsed = text
                )
                chatHistory.add(chatMsg)
                launch(Dispatchers.IO) { chatDatabase.chatDao().insertMessage(chatMsg) }

                // Record telemetry
                telemetryManager.recordEvent(
                    intentTag = intent.tag,
                    responseTimeMs = responseTimeMs,
                    emotion = emotionalContext.currentEmotion.name,
                    success = true
                )

                // Record personalisation
                launch(Dispatchers.IO) {
                    personalisationEngine.recordInteraction(intent.tag, text)
                }

                statusText.text = "Arcle Intelligence — Ready"

            } catch (e: Exception) {
                Log.e(TAG, "Error processing input", e)
                addAssistantMessage(ArcleResponses.random(ArcleResponses.ERROR_MESSAGES))
                statusText.text = "Arcle Intelligence — Ready"
            }
        }
    }

    private fun addUserMessage(text: String) {
        val msgView = TextView(this).apply {
            this.text = text
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            setBackgroundColor(android.graphics.Color.parseColor("#6E56CF"))
            setPadding(24, 16, 24, 16)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                topMargin = 12
                bottomMargin = 4
            }
            layoutParams = params
        }
        chatContainer.addView(msgView)
        scrollToBottom()
    }

    private fun addAssistantMessage(text: String) {
        val msgView = TextView(this).apply {
            this.text = text
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
            setPadding(24, 16, 24, 16)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START
                topMargin = 4
                bottomMargin = 12
            }
            layoutParams = params
        }
        chatContainer.addView(msgView)
        scrollToBottom()
    }

    private fun addImageMessage(bitmap: Bitmap) {
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12; bottomMargin = 12 }
            layoutParams = params
        }
        chatContainer.addView(imageView)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        unregisterReceiver(wakeWordReceiver)
        mainScope.cancel()
        qwen3VLEngine.release()
        sherpaSttEngine.release()
        sherpaTtsEngine.release()
        sdxsEngine.release()
        t2vEngine.release()
        yolo11nEngine.release()
        super.onDestroy()
    }
}
