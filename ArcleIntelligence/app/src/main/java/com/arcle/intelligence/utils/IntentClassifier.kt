package com.arcle.intelligence.utils

/**
 * Intent Classifier.
 * Maps STT output to exactly ONE intent tag and routes accordingly.
 * Covers all 27 intent tags specified in the routing table.
 */
object IntentClassifier {

    enum class IntentTag(val tag: String) {
        IMAGE("[IMAGE]"),
        VIDEO("[VIDEO]"),
        DEEP_RESEARCH("[DEEP_RESEARCH]"),
        VISION_UNDERSTAND("[VISION_UNDERSTAND]"),
        VISION_VIDEO_UNDERSTAND("[VISION_VIDEO_UNDERSTAND]"),
        VISION_OCR("[VISION_OCR]"),
        VISION_DOC("[VISION_DOC]"),
        VISION_CODE("[VISION_CODE]"),
        VISION_MATH("[VISION_MATH]"),
        VISION_LOCATE("[VISION_LOCATE]"),
        AUTO_OFFLINE("[AUTO_OFFLINE]"),
        AUTO_NET("[AUTO_NET]"),
        AUTO_CALL("[AUTO_CALL]"),
        AUTO_MSG("[AUTO_MSG]"),
        AUTO_SYSTEM("[AUTO_SYSTEM]"),
        CODE_APP("[CODE_APP]"),
        CODE_WEB("[CODE_WEB]"),
        CODE_GAME("[CODE_GAME]"),
        CODE_SCRIPT("[CODE_SCRIPT]"),
        CODE_API("[CODE_API]"),
        MATH("[MATH]"),
        TRANSLATE("[TRANSLATE]"),
        WEATHER("[WEATHER]"),
        REMINDER("[REMINDER]"),
        CREATIVE("[CREATIVE]"),
        IDENTITY("[IDENTITY]"),
        CHAT("[CHAT]")
    }

    data class ClassificationResult(
        val intent: IntentTag,
        val confidence: Float,
        val extractedParams: Map<String, String> = emptyMap()
    )

    // ── Keyword patterns for each intent ──────────────────────────────────────

    private val deepResearchPatterns = listOf(
        "deep research", "multilevel research", "multi-level research",
        "research everything", "find all information", "search deeply",
        "investigate thoroughly", "comprehensive search", "investigate",
        "research everything about", "do deep research"
    )

    private val imagePatterns = listOf(
        "generate an image", "generate image", "create an image", "make a picture",
        "draw", "generate a picture", "create art", "make art", "generate art",
        "make a wallpaper", "create wallpaper", "generate a photo", "make a visual",
        "paint", "illustrate", "design an image", "picture of"
    )

    private val videoPatterns = listOf(
        "generate a video", "make a video", "create a video", "generate video",
        "make video", "create video", "video of", "make a clip", "generate a clip"
    )

    private val autoNetApps = mapOf(
        "youtube" to "com.google.android.youtube",
        "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana",
        "whatsapp" to "com.whatsapp",
        "snapchat" to "com.snapchat.android",
        "gmail" to "com.google.android.gm",
        "chrome" to "com.android.chrome",
        "spotify" to "com.spotify.music",
        "maps" to "com.google.android.apps.maps",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "tiktok" to "com.zhiliaoapp.musically",
        "telegram" to "org.telegram.messenger",
        "netflix" to "com.netflix.mediaclient",
        "amazon" to "com.amazon.mShop.android.shopping",
        "reddit" to "com.reddit.frontpage",
        "discord" to "com.discord",
        "linkedin" to "com.linkedin.android",
        "pinterest" to "com.pinterest"
    )

    private val autoOfflinePatterns = listOf(
        "flashlight", "torch", "volume", "mute", "unmute",
        "bluetooth", "screenshot", "dark mode", "night mode",
        "battery saver", "airplane mode", "flight mode",
        "alarm", "calculator", "gallery", "photos", "brightness",
        "rotate", "rotation", "silent", "vibrate", "do not disturb",
        "wifi", "wi-fi", "hotspot", "nfc"
    )

    private val callPatterns = listOf(
        "call", "phone call", "dial", "ring", "make a call"
    )

    private val messagePatterns = listOf(
        "text", "send a message", "message", "sms", "send text",
        "send a text", "send sms", "whatsapp message", "text message"
    )

    private val systemPatterns = listOf(
        "battery", "storage", "ram", "memory", "device info",
        "system info", "phone info", "how much storage", "how much battery"
    )

    private val codeAppPatterns = listOf(
        "android app", "ios app", "mobile app", "create an app",
        "build an app", "make an app", "develop an app"
    )

    private val codeWebPatterns = listOf(
        "website", "web page", "html page", "create a website",
        "build a website", "make a website", "landing page", "web app"
    )

    private val codeGamePatterns = listOf(
        "game", "create a game", "make a game", "build a game",
        "snake game", "tic tac toe", "pong", "tetris", "flappy"
    )

    private val codeScriptPatterns = listOf(
        "script", "bot", "automation", "write a script",
        "create a bot", "python script", "bash script"
    )

    private val codeApiPatterns = listOf(
        "api", "rest api", "backend", "server", "endpoint",
        "build an api", "create an api"
    )

    private val mathPatterns = listOf(
        "solve", "calculate", "equation", "math", "what is",
        "how much is", "multiply", "divide", "add ", "subtract",
        "percentage", "square root", "factorial", "derivative", "integral"
    )

    private val translatePatterns = listOf(
        "translate", "translation", "how do you say", "in japanese",
        "in french", "in spanish", "in hindi", "in german",
        "in chinese", "in korean", "in arabic", "in portuguese"
    )

    private val weatherPatterns = listOf(
        "weather", "temperature", "forecast", "rain today",
        "is it going to rain", "how hot", "how cold", "climate"
    )

    private val reminderPatterns = listOf(
        "remind me", "reminder", "set a reminder", "alert me",
        "remind", "don't let me forget", "notify me", "wake me up"
    )

    private val creativePatterns = listOf(
        "story", "poem", "joke", "rap", "song", "write a story",
        "write a poem", "tell me a joke", "creative", "haiku",
        "limerick", "rhyme", "fairy tale", "fiction"
    )

    private val identityPatterns = listOf(
        "who are you", "what is your name", "your name", "who created you",
        "who made you", "who built you", "are you chatgpt", "are you gemini",
        "are you siri", "are you alexa", "what can you do", "your capabilities",
        "your version", "how old are you", "where do you live", "who is your creator",
        "what are you", "are you human", "are you ai", "are you real"
    )

    private val visionOcrPatterns = listOf(
        "read this", "extract text", "ocr", "read the text",
        "what does it say", "read that", "read the sign"
    )

    private val visionMathPatterns = listOf(
        "solve this", "solve this equation", "solve this math",
        "what is the answer", "calculate this"
    )

    private val visionCodePatterns = listOf(
        "build this app", "create this", "code this",
        "build this", "make this app", "implement this design"
    )

    private val visionDocPatterns = listOf(
        "parse this", "read this document", "extract data",
        "read this chart", "analyze this document", "this invoice"
    )

    private val visionLocatePatterns = listOf(
        "find", "locate", "where is", "point out", "highlight"
    )

    /**
     * Classify user input into exactly one intent tag.
     */
    fun classify(
        userInput: String,
        hasImage: Boolean = false,
        hasVideo: Boolean = false
    ): ClassificationResult {
        val input = userInput.lowercase().trim()
        val originalInput = userInput.trim()

        // ── Priority 1: Check if LLM response starts with an intent tag ──────
        for (tag in IntentTag.entries) {
            if (input.startsWith(tag.tag.lowercase())) {
                return ClassificationResult(tag, 1.0f)
            }
        }

        // ── Priority 2: Vision intents (if media is attached) ─────────────────
        if (hasVideo) {
            return ClassificationResult(IntentTag.VISION_VIDEO_UNDERSTAND, 0.9f)
        }

        if (hasImage) {
            return classifyVisionIntent(input)
        }

        // ── Priority 3: Deep Research (check before general chat) ─────────────
        if (deepResearchPatterns.any { input.contains(it) }) {
            return ClassificationResult(IntentTag.DEEP_RESEARCH, 0.95f)
        }

        // ── Priority 4: Image generation ──────────────────────────────────────
        if (imagePatterns.any { input.contains(it) }) {
            return ClassificationResult(IntentTag.IMAGE, 0.9f)
        }

        // ── Priority 5: Video generation ──────────────────────────────────────
        if (videoPatterns.any { input.contains(it) }) {
            return ClassificationResult(IntentTag.VIDEO, 0.9f)
        }

        // ── Priority 6: Identity ──────────────────────────────────────────────
        if (identityPatterns.any { input.contains(it) }) {
            return ClassificationResult(IntentTag.IDENTITY, 0.95f)
        }

        // ── Priority 7: Code generation (BEFORE auto-offline to prevent
        //    "calculator" in "Create a calculator Android app" from matching
        //    AUTO_OFFLINE instead of CODE_APP) ──────────────────────────────────
        if (codeGamePatterns.any { input.contains(it) }) {
            return ClassificationResult(IntentTag.CODE_GAME, 0.9f)
        }
        if (codeAppPatterns.any { input.contains(it) }) {
            return ClassificationResult(IntentTag.CODE_APP, 0.9f)
        }
        if (codeWebPatterns.any { input.contains(it) }) {
            return ClassificationResult(IntentTag.CODE_WEB, 0.9f)
        }
        if (codeApiPatterns.any { input.contains(it) }) {
            return ClassificationResult(IntentTag.CODE_API, 0.9f)
        }
        if (codeScriptPatterns.any { input.contains(it) }) {
            return ClassificationResult(IntentTag.CODE_SCRIPT, 0.9f)
        }

        // ── Priority 8: Automation (offline) ──────────────────────────────────
        if (autoOfflinePatterns.any { input.contains(it) }) {
            val isOpen = input.contains("open") || input.contains("launch")
            // If "open" + internet app → AUTO_NET, else AUTO_OFFLINE
            if (isOpen) {
                val app = autoNetApps.entries.firstOrNull { input.contains(it.key) }
                if (app != null) {
                    return ClassificationResult(
                        IntentTag.AUTO_NET, 0.95f,
                        mapOf("appName" to app.key, "packageName" to app.value)
                    )
                }
            }

            val params = mutableMapOf<String, String>()
            if (input.contains("alarm")) {
                // Extract time if present
                val timeRegex = Regex("""(\d{1,2}[:.]\d{2}\s*(am|pm)?)|(\d{1,2}\s*(am|pm))""", RegexOption.IGNORE_CASE)
                timeRegex.find(input)?.let { params["time"] = it.value }
            }
            return ClassificationResult(IntentTag.AUTO_OFFLINE, 0.9f, params)
        }

        // ── Priority 9: Internet apps ─────────────────────────────────────────
        if (input.contains("open") || input.contains("launch") || input.contains("start")) {
            val app = autoNetApps.entries.firstOrNull { input.contains(it.key) }
            if (app != null) {
                return ClassificationResult(
                    IntentTag.AUTO_NET, 0.95f,
                    mapOf("appName" to app.key, "packageName" to app.value)
                )
            }
        }

        // ── Priority 10: Calling ──────────────────────────────────────────────
        if (callPatterns.any { input.contains(it) }) {
            val contactName = extractContactName(input)
            return ClassificationResult(
                IntentTag.AUTO_CALL, 0.9f,
                mapOf("contact" to contactName)
            )
        }

        // ── Priority 11: Messaging ────────────────────────────────────────────
        if (messagePatterns.any { input.contains(it) }) {
            val (contact, message) = extractMessageParams(originalInput)
            return ClassificationResult(
                IntentTag.AUTO_MSG, 0.9f,
                mapOf("contact" to contact, "message" to message)
            )
        }

        // ── Priority 12: System info ──────────────────────────────────────────
        if (systemPatterns.any { input.contains(it) }) {
            return ClassificationResult(IntentTag.AUTO_SYSTEM, 0.85f)
        }

        // ── Priority 13: Weather ──────────────────────────────────────────────
        if (weatherPatterns.any { input.contains(it) }) {
            return ClassificationResult(IntentTag.WEATHER, 0.9f)
        }

        // ── Priority 14: Reminders ────────────────────────────────────────────
        if (reminderPatterns.any { input.contains(it) }) {
            val (task, time) = extractReminderParams(input)
            return ClassificationResult(
                IntentTag.REMINDER, 0.9f,
                mapOf("task" to task, "time" to time)
            )
        }

        // ── Priority 15: Math ─────────────────────────────────────────────────
        if (mathPatterns.any { input.contains(it) } ||
            input.matches(Regex(".*\\d+\\s*[+\\-*/^]\\s*\\d+.*"))) {
            return ClassificationResult(IntentTag.MATH, 0.85f)
        }

        // ── Priority 16: Translation ──────────────────────────────────────────
        if (translatePatterns.any { input.contains(it) }) {
            return ClassificationResult(IntentTag.TRANSLATE, 0.9f)
        }

        // ── Priority 17: Creative ─────────────────────────────────────────────
        if (creativePatterns.any { input.contains(it) }) {
            return ClassificationResult(IntentTag.CREATIVE, 0.85f)
        }

        // ── Default: General Chat ─────────────────────────────────────────────
        return ClassificationResult(IntentTag.CHAT, 0.7f)
    }

    private fun classifyVisionIntent(input: String): ClassificationResult {
        return when {
            visionMathPatterns.any { input.contains(it) } &&
                (input.contains("math") || input.contains("equation") || input.contains("solve")) ->
                ClassificationResult(IntentTag.VISION_MATH, 0.9f)

            visionOcrPatterns.any { input.contains(it) } ->
                ClassificationResult(IntentTag.VISION_OCR, 0.9f)

            visionCodePatterns.any { input.contains(it) } ->
                ClassificationResult(IntentTag.VISION_CODE, 0.9f)

            visionDocPatterns.any { input.contains(it) } ->
                ClassificationResult(IntentTag.VISION_DOC, 0.9f)

            visionLocatePatterns.any { input.contains(it) } ->
                ClassificationResult(IntentTag.VISION_LOCATE, 0.85f)

            else -> ClassificationResult(IntentTag.VISION_UNDERSTAND, 0.8f)
        }
    }

    private fun extractContactName(input: String): String {
        val callRemoved = input.replace(Regex("(call|phone call|dial|ring|make a call)"), "").trim()
        return callRemoved.replace(Regex("^(to\\s+|for\\s+)"), "").trim().ifEmpty { "unknown" }
    }

    private fun extractMessageParams(input: String): Pair<String, String> {
        // Pattern: "text/message [contact]: [message]" or "text [contact] [message]"
        val colonMatch = Regex("""(?:text|message|sms)\s+(.+?):\s*(.+)""", RegexOption.IGNORE_CASE).find(input)
        if (colonMatch != null) {
            return Pair(colonMatch.groupValues[1].trim(), colonMatch.groupValues[2].trim())
        }

        // Pattern: "send [contact] [message]"
        val words = input.split(" ").filter { it.isNotEmpty() }
        val contactIdx = words.indexOfFirst { it.matches(Regex("[A-Z].*")) }
        return if (contactIdx >= 0 && contactIdx < words.size - 1) {
            Pair(words[contactIdx], words.subList(contactIdx + 1, words.size).joinToString(" "))
        } else {
            Pair("unknown", input)
        }
    }

    private fun extractReminderParams(input: String): Pair<String, String> {
        // Pattern: "remind me to [task] at/in [time]"
        val atMatch = Regex("""remind\s+me\s+to\s+(.+?)\s+(?:at|in)\s+(.+)""", RegexOption.IGNORE_CASE).find(input)
        if (atMatch != null) {
            return Pair(atMatch.groupValues[1].trim(), atMatch.groupValues[2].trim())
        }

        // Pattern: "remind me to [task] every [interval]"
        val everyMatch = Regex("""remind\s+me\s+to\s+(.+?)\s+every\s+(.+)""", RegexOption.IGNORE_CASE).find(input)
        if (everyMatch != null) {
            return Pair(everyMatch.groupValues[1].trim(), "every ${everyMatch.groupValues[2].trim()}")
        }

        return Pair(input, "")
    }
}
