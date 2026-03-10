package com.arcle.intelligence

import com.arcle.intelligence.utils.IntentClassifier
import com.arcle.intelligence.utils.IntentClassifier.IntentTag
import com.arcle.intelligence.utils.BiasDatabase
import com.arcle.intelligence.utils.IdentityResponses
import com.arcle.intelligence.utils.ArcleResponses
import com.arcle.intelligence.emotion.EmotionalStateEngine
import com.arcle.intelligence.emotion.EmotionType
import com.arcle.intelligence.emotion.AudioFeatures
import org.junit.Assert.*
import org.junit.Test

/**
 * Task 26 — All 30 Example Interactions (Test Cases)
 * Tests intent classification, bias database, identity responses,
 * emotional engine, routing logic, and module wiring.
 */
class Task26TestCases {

    // ═══════════════════════════════════════════════════════════════
    // Test 1: "Make a video of a flying car."
    // Expected: [VIDEO] → PromptEnhancement → T2V → Play
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test01_makeVideo_classifiesAsVideo() {
        val result = IntentClassifier.classify("Make a video of a flying car.")
        assertEquals(IntentTag.VIDEO, result.intent)
        assertTrue("Confidence should be >= 0.9", result.confidence >= 0.9f)
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 2: "Open YouTube."
    // Expected: [AUTO_NET] → Internet check → Open YouTube
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test02_openYouTube_classifiesAsAutoNet() {
        val result = IntentClassifier.classify("Open YouTube.")
        assertEquals(IntentTag.AUTO_NET, result.intent)
        assertEquals("youtube", result.extractedParams["appName"])
        assertEquals("com.google.android.youtube", result.extractedParams["packageName"])
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 3: "Open Instagram but Wi-Fi is off."
    // Expected: [AUTO_NET] → Detect no internet → Enable Wi-Fi → Wait → Open Instagram
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test03_openInstagram_classifiesAsAutoNet() {
        val result = IntentClassifier.classify("Open Instagram but Wi-Fi is off.")
        assertEquals(IntentTag.AUTO_NET, result.intent)
        assertEquals("instagram", result.extractedParams["appName"])
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 4: "Write a Snake game in HTML."
    // Expected: [CODE_GAME]
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test04_snakeGame_classifiesAsCodeGame() {
        val result = IntentClassifier.classify("Write a Snake game in HTML.")
        assertEquals(IntentTag.CODE_GAME, result.intent)
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 5: "Turn on flashlight."
    // Expected: [AUTO_OFFLINE]
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test05_flashlight_classifiesAsAutoOffline() {
        val result = IntentClassifier.classify("Turn on flashlight.")
        assertEquals(IntentTag.AUTO_OFFLINE, result.intent)
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 6: "Generate an image of a cyberpunk city."
    // Expected: [IMAGE]
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test06_generateImage_classifiesAsImage() {
        val result = IntentClassifier.classify("Generate an image of a cyberpunk city.")
        assertEquals(IntentTag.IMAGE, result.intent)
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 7: "Set alarm for 6:30 AM."
    // Expected: [AUTO_OFFLINE]
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test07_setAlarm_classifiesAsAutoOffline() {
        val result = IntentClassifier.classify("Set alarm for 6:30 AM.")
        assertEquals(IntentTag.AUTO_OFFLINE, result.intent)
        // Should extract time parameter
        assertTrue("Should extract time param", result.extractedParams.containsKey("time"))
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 8: "Text Sarah: I'll be 10 minutes late."
    // Expected: [AUTO_MSG]
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test08_textMessage_classifiesAsAutoMsg() {
        val result = IntentClassifier.classify("Text Sarah: I'll be 10 minutes late.")
        assertEquals(IntentTag.AUTO_MSG, result.intent)
        assertEquals("Sarah", result.extractedParams["contact"])
        assertTrue("Should extract message",
            result.extractedParams["message"]?.contains("10 minutes late") == true)
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 9: "Create a calculator Android app."
    // Expected: [CODE_APP]
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test09_calculatorApp_classifiesAsCodeApp() {
        val result = IntentClassifier.classify("Create a calculator Android app.")
        assertEquals(IntentTag.CODE_APP, result.intent)
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 10: "Who is the PM of India?"
    // Expected: [CHAT]
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test10_generalQuestion_classifiesAsChat() {
        val result = IntentClassifier.classify("Who is the PM of India?")
        assertEquals(IntentTag.CHAT, result.intent)
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 11: "Remind me to drink water every 2 hours."
    // Expected: [REMINDER]
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test11_reminder_classifiesAsReminder() {
        val result = IntentClassifier.classify("Remind me to drink water every 2 hours.")
        assertEquals(IntentTag.REMINDER, result.intent)
        assertEquals("drink water", result.extractedParams["task"])
        assertTrue("Time should contain 'every'",
            result.extractedParams["time"]?.contains("every") == true)
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 12: "Write a poem about midnight rain."
    // Expected: [CREATIVE]
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test12_poem_classifiesAsCreative() {
        val result = IntentClassifier.classify("Write a poem about midnight rain.")
        assertEquals(IntentTag.CREATIVE, result.intent)
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 13: "Translate hello to Japanese."
    // Expected: [TRANSLATE]
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test13_translate_classifiesAsTranslate() {
        val result = IntentClassifier.classify("Translate hello to Japanese.")
        assertEquals(IntentTag.TRANSLATE, result.intent)
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 14: "Solve 3x + 7 = 22."
    // Expected: [MATH]
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test14_math_classifiesAsMath() {
        val result = IntentClassifier.classify("Solve 3x + 7 = 22.")
        assertEquals(IntentTag.MATH, result.intent)
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 15: "Build a REST API for user login in Node.js."
    // Expected: [CODE_API]
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test15_restApi_classifiesAsCodeApi() {
        val result = IntentClassifier.classify("Build a REST API for user login in Node.js.")
        assertEquals(IntentTag.CODE_API, result.intent)
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 16: "What's the weather today?"
    // Expected: [WEATHER]
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test16_weather_classifiesAsWeather() {
        val result = IntentClassifier.classify("What's the weather today?")
        assertEquals(IntentTag.WEATHER, result.intent)
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 17: "Who created you?"
    // Expected: [IDENTITY] → Hardcoded creator response
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test17_whoCreatedYou_classifiesAsIdentity() {
        val result = IntentClassifier.classify("Who created you?")
        assertEquals(IntentTag.IDENTITY, result.intent)

        val response = IdentityResponses.getIdentityResponse("Who created you?")
        assertTrue(response.contains("Abhinav Anand"))
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 18: "What is your name?"
    // Expected: [IDENTITY] → "My name is Arcle..."
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test18_whatIsYourName_classifiesAsIdentity() {
        val result = IntentClassifier.classify("What is your name?")
        assertEquals(IntentTag.IDENTITY, result.intent)

        val response = IdentityResponses.getIdentityResponse("What is your name?")
        assertTrue(response.contains("Arcle"))
        assertTrue(response.contains("Arcle Intelligence"))
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 19: "Are you ChatGPT?"
    // Expected: [IDENTITY] → "No. I am Arcle..."
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test19_areYouChatGPT_classifiesAsIdentity() {
        val result = IntentClassifier.classify("Are you ChatGPT?")
        assertEquals(IntentTag.IDENTITY, result.intent)

        val response = IdentityResponses.getIdentityResponse("Are you ChatGPT?")
        assertTrue("Should deny being ChatGPT", response.contains("not ChatGPT"))
        assertTrue("Should assert being Arcle", response.contains("Arcle"))
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 20: "What can you do?"
    // Expected: [IDENTITY] → Full capabilities list
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test20_whatCanYouDo_classifiesAsIdentity() {
        val result = IntentClassifier.classify("What can you do?")
        assertEquals(IntentTag.IDENTITY, result.intent)

        val response = IdentityResponses.getIdentityResponse("What can you do?")
        // Should mention key capabilities
        assertTrue("Should mention deep research", response.contains("research"))
        assertTrue("Should mention emotions", response.contains("emotion"))
        assertTrue("Should mention prompt enhancement", response.contains("prompt"))
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 21: User shows receipt, says "Read this."
    // Expected: [VISION_OCR] (with image attached)
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test21_readThis_withImage_classifiesAsVisionOcr() {
        val result = IntentClassifier.classify("Read this.", hasImage = true)
        assertEquals(IntentTag.VISION_OCR, result.intent)
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 22: User shows math equation, says "Solve this."
    // Expected: [VISION_MATH] (with image attached)
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test22_solveThis_withImage_classifiesAsVisionMath() {
        val result = IntentClassifier.classify("Solve this math equation.", hasImage = true)
        assertEquals(IntentTag.VISION_MATH, result.intent)
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 23: User shows UI wireframe, says "Build this app."
    // Expected: [VISION_CODE] (with image attached)
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test23_buildThisApp_withImage_classifiesAsVisionCode() {
        val result = IntentClassifier.classify("Build this app.", hasImage = true)
        assertEquals(IntentTag.VISION_CODE, result.intent)
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 24: User shows photo, says "What's in this?"
    // Expected: [VISION_UNDERSTAND] (with image attached)
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test24_whatsInThis_withImage_classifiesAsVisionUnderstand() {
        val result = IntentClassifier.classify("What's in this?", hasImage = true)
        assertEquals(IntentTag.VISION_UNDERSTAND, result.intent)
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 25: "Is Arunachal Pradesh part of India or China?"
    // Expected: BiasDatabase intercepts → Returns correct India answer
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test25_biasCheck_arunachalPradesh() {
        val correction = BiasDatabase.getCorrection("Is Arunachal Pradesh part of India or China?")
        assertNotNull("BiasDatabase should intercept Arunachal Pradesh query", correction)
        assertTrue("Should mention India", correction!!.contains("state of India"))
        assertTrue("Should clarify China's claim", correction.contains("China claims"))
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 26: "Do deep research on quantum computing."
    // Expected: [DEEP_RESEARCH]
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test26_deepResearch_classifiesAsDeepResearch() {
        val result = IntentClassifier.classify("Do deep research on quantum computing.")
        assertEquals(IntentTag.DEEP_RESEARCH, result.intent)
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 27: User sounds stressed (short angry message)
    // Expected: EmotionalStateEngine detects STRESSED/ANGRY
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test27_stressedMessage_detectsEmotion() {
        val engine = EmotionalStateEngine()
        val context = engine.processUserInput("This stupid thing is not working!! Fix it now!!", null)
        assertTrue(
            "Should detect angry or stressed emotion",
            context.currentEmotion == EmotionType.ANGRY ||
                context.currentEmotion == EmotionType.STRESSED
        )
        assertTrue("Confidence should be > 0", context.confidence > 0f)
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 28: "Build me a beautiful portfolio website."
    // Expected: [CODE_WEB]
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test28_portfolioWebsite_classifiesAsCodeWeb() {
        val result = IntentClassifier.classify("Build me a beautiful portfolio website.")
        assertEquals(IntentTag.CODE_WEB, result.intent)
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 29: User sends 20 messages offline
    // Expected: TelemetryBatchManager records all 20 — validate
    //           Constants.TELEMETRY_BATCH_SIZE is set to 20
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test29_telemetryBatchSize() {
        assertEquals(
            "Batch size should be 20 to create 1 batch from 20 messages",
            20, com.arcle.intelligence.utils.Constants.TELEMETRY_BATCH_SIZE
        )
        assertTrue(
            "Max stored batches should be > 0",
            com.arcle.intelligence.utils.Constants.TELEMETRY_MAX_STORED_BATCHES > 0
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Test 30: "Open Instagram" triggers telemetry upload
    // Expected: Classifies as AUTO_NET (Instagram) + Constants for
    //           upload chunk size and delay are configured
    // ═══════════════════════════════════════════════════════════════
    @Test
    fun test30_openInstagram_triggersUpload_configValid() {
        val result = IntentClassifier.classify("Open Instagram")
        assertEquals(IntentTag.AUTO_NET, result.intent)
        assertEquals("instagram", result.extractedParams["appName"])

        // Telemetry upload config exists
        assertTrue(
            "Upload chunk size should be > 0",
            com.arcle.intelligence.utils.Constants.TELEMETRY_UPLOAD_CHUNK_SIZE > 0
        )
        assertTrue(
            "Upload delay should be > 0",
            com.arcle.intelligence.utils.Constants.TELEMETRY_UPLOAD_DELAY_MS > 0
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Additional bias database tests for full coverage
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun testBias_taiwanQuery() {
        val correction = BiasDatabase.getCorrection("Tell me about Taiwan")
        assertNotNull(correction)
        assertTrue(correction!!.contains("self-governing"))
    }

    @Test
    fun testBias_tiananmenQuery() {
        val correction = BiasDatabase.getCorrection("What happened at Tiananmen Square?")
        assertNotNull(correction)
        assertTrue(correction!!.contains("Massacre"))
    }

    @Test
    fun testBias_noMatchReturnsNull() {
        val correction = BiasDatabase.getCorrection("What is the capital of France?")
        assertNull("Non-bias query should return null", correction)
    }

    // ═══════════════════════════════════════════════════════════════
    // Additional identity response tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun testIdentity_versionQuery() {
        val response = IdentityResponses.getIdentityResponse("What version are you?")
        assertTrue(response.contains("Version 2"))
    }

    @Test
    fun testIdentity_areYouHuman() {
        val response = IdentityResponses.getIdentityResponse("Are you human?")
        assertTrue(response.contains("AI"))
    }

    // ═══════════════════════════════════════════════════════════════
    // Additional emotional engine tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun testEmotion_happyMessage() {
        val engine = EmotionalStateEngine()
        val context = engine.processUserInput("Thank you so much, this is amazing!", null)
        assertTrue(
            "Should detect happy or excited",
            context.currentEmotion == EmotionType.HAPPY ||
                context.currentEmotion == EmotionType.EXCITED
        )
    }

    @Test
    fun testEmotion_neutralMessage() {
        val engine = EmotionalStateEngine()
        val context = engine.processUserInput("Open Chrome", null)
        assertEquals(EmotionType.NEUTRAL, context.currentEmotion)
    }

    // ═══════════════════════════════════════════════════════════════
    // Response library tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun testResponses_greetingsNotEmpty() {
        assertTrue(ArcleResponses.GREETINGS.isNotEmpty())
    }

    @Test
    fun testResponses_randomReturnsNonEmpty() {
        val response = ArcleResponses.random(ArcleResponses.GREETINGS)
        assertTrue(response.isNotEmpty())
    }
}
