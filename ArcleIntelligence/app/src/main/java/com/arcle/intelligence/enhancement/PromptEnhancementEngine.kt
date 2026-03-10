package com.arcle.intelligence.enhancement

import com.arcle.intelligence.emotion.EmotionalContext

/**
 * Universal Prompt Enhancement Engine.
 * Every prompt the user gives is automatically enhanced BEFORE being passed to any model.
 * The user never needs to know how to write a good prompt — Arcle handles it internally.
 *
 * For prompts that require AI enhancement (IMAGE, VIDEO, CODE, CREATIVE), the engine
 * will call Qwen3VLEngine. For simpler enhancements, static templates are used.
 */
object PromptEnhancementEngine {

    /**
     * Callback interface for AI-powered prompt enhancement.
     * This is set by Qwen3VLEngine at initialization time to avoid circular dependencies.
     */
    var aiEnhancer: ((prompt: String) -> String)? = null

    fun enhance(rawPrompt: String, intentTag: String): String {
        return when (intentTag) {
            "[IMAGE]" -> enhanceImagePrompt(rawPrompt)
            "[VIDEO]" -> enhanceVideoPrompt(rawPrompt)
            "[CODE_APP]" -> enhanceCodePrompt(rawPrompt, "Android/mobile application")
            "[CODE_WEB]" -> enhanceCodePrompt(rawPrompt, "website")
            "[CODE_GAME]" -> enhanceGamePrompt(rawPrompt)
            "[CODE_SCRIPT]" -> enhanceScriptPrompt(rawPrompt)
            "[CODE_API]" -> enhanceApiPrompt(rawPrompt)
            "[CREATIVE]" -> enhanceCreativePrompt(rawPrompt)
            "[VISION_CODE]" -> enhanceVisionCodePrompt(rawPrompt)
            "[DEEP_RESEARCH]" -> enhanceResearchPrompt(rawPrompt)
            else -> rawPrompt
        }
    }

    private fun enhanceImagePrompt(raw: String): String {
        val enhancementPrompt = """
            Enhance this image generation prompt to be extremely detailed and vivid.
            Original: "$raw"
            
            Add: specific lighting (golden hour, studio lighting, etc.), atmosphere,
            style (photorealistic, cinematic, digital art, etc.), camera details
            (wide angle, portrait, macro, etc.), color palette, mood, and technical
            quality descriptors.
            
            Return ONLY the enhanced prompt. Nothing else. No explanation.
            The enhanced prompt should be 2-4x longer than the original.
        """.trimIndent()

        val aiEnhanced = aiEnhancer?.invoke(enhancementPrompt)
            ?: raw

        return "$aiEnhanced, photorealistic, 8K ultra-detailed, cinematic lighting, " +
               "sharp focus, professional photography, award-winning composition"
    }

    private fun enhanceVideoPrompt(raw: String): String {
        val enhancementPrompt = """
            Enhance this video generation prompt for maximum visual quality.
            Original: "$raw"
            Add: camera movement (pan, zoom, tracking shot), scene atmosphere,
            lighting conditions, time of day, action details, visual style.
            Return ONLY the enhanced prompt. No explanation.
        """.trimIndent()

        val aiEnhanced = aiEnhancer?.invoke(enhancementPrompt)
            ?: raw

        return "$aiEnhanced, cinematic, smooth motion, high framerate, 4K quality, " +
               "professional cinematography"
    }

    private fun enhanceCodePrompt(raw: String, type: String): String {
        val enhancementPrompt = """
            The user wants to build a $type. Their request: "$raw"
            
            Expand this into a comprehensive specification that includes:
            - All features a professional version would have (not just what they asked)
            - Modern, beautiful UI requirements (gradients, animations, 3D effects)
            - Technical requirements (error handling, validation, persistence)
            - User experience details (loading states, empty states, feedback)
            - Performance requirements
            
            Return ONLY the enhanced specification. No explanation.
            Write it as if you are briefing a senior developer.
        """.trimIndent()

        return aiEnhancer?.invoke(enhancementPrompt)
            ?: ("$raw\n\nRequirements: Modern beautiful UI with gradients and animations, " +
                "complete error handling, input validation, data persistence, " +
                "loading states, responsive layout, professional design.")
    }

    private fun enhanceGamePrompt(raw: String): String {
        val enhancementPrompt = """
            The user wants to build a game: "$raw"
            
            Expand into a complete game design document including:
            - Core gameplay loop
            - Controls (keyboard + mobile touch)
            - Progression system (levels, difficulty scaling)
            - Visual style (colors, effects, animations)
            - Audio design (sound effects needed)
            - Game over and restart flow
            - Score system
            
            Return ONLY the enhanced game design. No explanation.
        """.trimIndent()

        return aiEnhancer?.invoke(enhancementPrompt)
            ?: ("$raw\n\nRequirements: Complete game loop (start/play/game over/restart), " +
                "keyboard and touch controls, score counter, at least 2 difficulty levels, " +
                "sound effects, smooth animations, mobile responsive.")
    }

    private fun enhanceScriptPrompt(raw: String): String {
        return "$raw\n\nRequirements: Include full error handling, logging, " +
               "command-line argument support, usage documentation, and example runs."
    }

    private fun enhanceApiPrompt(raw: String): String {
        return "$raw\n\nRequirements: RESTful design, JWT authentication, input validation, " +
               "rate limiting, comprehensive error responses, API documentation, " +
               "database setup script, and Docker configuration."
    }

    private fun enhanceCreativePrompt(raw: String): String {
        val enhancementPrompt = """
            The user wants creative writing: "$raw"
            Enhance this into a detailed creative brief with: tone, style,
            emotional depth, narrative structure, key themes, and vivid imagery goals.
            Return ONLY the enhanced creative brief. No explanation.
        """.trimIndent()

        return aiEnhancer?.invoke(enhancementPrompt)
            ?: ("$raw\n\nWrite with emotional depth, vivid imagery, rich metaphors, " +
                "and engaging narrative flow. Create something memorable and moving.")
    }

    private fun enhanceVisionCodePrompt(raw: String): String {
        return "$raw\n\nCode requirements: Beautiful modern UI matching the design exactly, " +
               "all interactive elements working, responsive layout, smooth animations, " +
               "complete and runnable with zero additional setup."
    }

    private fun enhanceResearchPrompt(raw: String): String {
        return "$raw\n\nResearch depth: Comprehensive. Cover all aspects including " +
               "history, current state, key players, controversies, future outlook, " +
               "statistics, expert opinions, and multiple perspectives."
    }
}
