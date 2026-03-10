package com.arcle.intelligence.ai

import android.content.Context
import android.os.Environment
import android.util.Log
import com.arcle.intelligence.emotion.EmotionalContext
import com.arcle.intelligence.enhancement.PromptEnhancementEngine
import com.arcle.intelligence.memory.ChatMessage
import com.arcle.intelligence.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Code Generation Manager.
 * Handles all CODE_APP, CODE_WEB, CODE_GAME, CODE_SCRIPT, CODE_API intents.
 * Uses a continuation loop to ensure complete code output.
 */
class CodeGenerationManager(
    private val context: Context,
    private val qwen3VLEngine: Qwen3VLEngine
) {

    companion object {
        private const val TAG = "CodeGenerationManager"
        private const val MAX_CONTINUATION_LOOPS = 10
    }

    data class GeneratedProject(
        val projectName: String,
        val outputDir: File,
        val files: List<GeneratedFile>,
        val totalLines: Int
    )

    data class GeneratedFile(
        val relativePath: String,
        val content: String,
        val language: String
    )

    /**
     * Generate complete code project from user request.
     * Uses PromptEnhancementEngine and continuation loop to ensure all files are complete.
     */
    suspend fun generateCode(
        userRequest: String,
        intentTag: String,
        chatContext: List<ChatMessage>,
        emotionalContext: EmotionalContext
    ): GeneratedProject = withContext(Dispatchers.IO) {
        // Step 1: Enhance the prompt based on intent type
        val enhancedPrompt = PromptEnhancementEngine.enhance(userRequest, intentTag)
        Log.i(TAG, "Enhanced code prompt for $intentTag")

        // Step 2: Build code generation prompt
        val codePrompt = buildCodeGenerationPrompt(enhancedPrompt, intentTag)

        // Step 3: Code generation continuation loop — never stops until complete
        val fullOutput = StringBuilder()
        var currentPrompt = codePrompt
        var loopCount = 0
        var codeGenerationComplete = false

        while (!codeGenerationComplete && loopCount < MAX_CONTINUATION_LOOPS) {
            val output = qwen3VLEngine.generateResponse(currentPrompt, chatContext, emotionalContext)
            fullOutput.append(output)
            loopCount++

            if (outputContainsIncompleteCode(output)) {
                // Automatically continue from where it left off
                currentPrompt = buildContinuationPrompt(output)
                Log.i(TAG, "Code incomplete, continuing loop $loopCount")
            } else {
                codeGenerationComplete = true
            }
        }

        // Step 4: Parse output into files
        val files = parseCodeOutput(fullOutput.toString())

        // Step 5: Determine project name
        val projectName = extractProjectName(userRequest) ?: "ArcleProject_${System.currentTimeMillis()}"

        // Step 6: Save to output directory
        val outputDir = saveCodeToStorage(projectName, files)

        val totalLines = files.sumOf { it.content.lines().size }
        Log.i(TAG, "Code generation complete: $projectName, ${files.size} files, $totalLines lines")

        GeneratedProject(projectName, outputDir, files, totalLines)
    }

    private fun buildCodeGenerationPrompt(enhancedSpec: String, intentTag: String): String {
        val typeDescription = when (intentTag) {
            "[CODE_APP]" -> "a complete Android/mobile application"
            "[CODE_WEB]" -> "a complete website"
            "[CODE_GAME]" -> "a complete, fully playable game"
            "[CODE_SCRIPT]" -> "a complete script/automation"
            "[CODE_API]" -> "a complete backend REST API"
            else -> "a complete software project"
        }

        return """
Generate $typeDescription based on this specification:

$enhancedSpec

CRITICAL RULES:
1. Output ALL files completely. Never truncate. Never use placeholders.
2. Every file must be production-ready and work on first run.
3. Visually stunning UI with gradients, animations, 3D effects.
4. Include ALL necessary files (configs, manifests, styles, logic).
5. Wrap each file in a code block with the filename as a comment on the first line.

Format each file like this:
```filename: path/to/file.ext
[complete file contents]
```

Begin generating now. Output every single file completely.
        """.trimIndent()
    }

    private fun buildContinuationPrompt(previousOutput: String): String {
        val lastLines = previousOutput.takeLast(500)
        return """
Continue generating from where you stopped. Here is the end of your previous output:

$lastLines

Continue writing. Complete ALL remaining files. Do not repeat what was already written.
Do not stop until every file is 100% complete.
        """.trimIndent()
    }

    private fun outputContainsIncompleteCode(output: String): Boolean {
        val trimmed = output.trimEnd()

        // Check for incomplete indicators
        if (trimmed.endsWith("...") || trimmed.endsWith("// TODO") ||
            trimmed.endsWith("// continue") || trimmed.endsWith("// ...")) {
            return true
        }

        // Check for unclosed code blocks
        val openBlocks = output.count { it == '{' }
        val closeBlocks = output.count { it == '}' }
        if (openBlocks > closeBlocks + 2) return true

        // Check for incomplete function/class declarations
        if (trimmed.endsWith("{") || trimmed.endsWith(",")) return true

        return false
    }

    /**
     * Parse the LLM output into individual files.
     * Expects format: ```filename: path/to/file.ext\n[content]\n```
     */
    private fun parseCodeOutput(rawOutput: String): List<GeneratedFile> {
        val files = mutableListOf<GeneratedFile>()

        val filePattern = Regex(
            """```(?:filename:\s*)?(.+?)\n([\s\S]*?)```""",
            RegexOption.MULTILINE
        )

        filePattern.findAll(rawOutput).forEach { match ->
            val filePath = match.groupValues[1].trim()
            val content = match.groupValues[2].trim()

            if (filePath.isNotEmpty() && content.isNotEmpty()) {
                val language = inferLanguage(filePath)
                files.add(GeneratedFile(filePath, content, language))
            }
        }

        // If no files parsed with the pattern, try to extract the whole output as a single file
        if (files.isEmpty() && rawOutput.isNotBlank()) {
            val extension = if (rawOutput.contains("<html", ignoreCase = true)) ".html"
                else if (rawOutput.contains("fun ") || rawOutput.contains("class ")) ".kt"
                else if (rawOutput.contains("function ") || rawOutput.contains("const ")) ".js"
                else ".txt"
            files.add(GeneratedFile("output$extension", rawOutput, inferLanguage("output$extension")))
        }

        return files
    }

    private fun inferLanguage(filePath: String): String {
        return when {
            filePath.endsWith(".kt") -> "kotlin"
            filePath.endsWith(".java") -> "java"
            filePath.endsWith(".html") || filePath.endsWith(".htm") -> "html"
            filePath.endsWith(".css") -> "css"
            filePath.endsWith(".js") -> "javascript"
            filePath.endsWith(".ts") -> "typescript"
            filePath.endsWith(".py") -> "python"
            filePath.endsWith(".json") -> "json"
            filePath.endsWith(".xml") -> "xml"
            filePath.endsWith(".md") -> "markdown"
            filePath.endsWith(".sh") -> "bash"
            filePath.endsWith(".yaml") || filePath.endsWith(".yml") -> "yaml"
            filePath.endsWith(".swift") -> "swift"
            filePath.endsWith(".dart") -> "dart"
            filePath.endsWith(".go") -> "go"
            filePath.endsWith(".rs") -> "rust"
            filePath.endsWith(".c") || filePath.endsWith(".h") -> "c"
            filePath.endsWith(".cpp") || filePath.endsWith(".hpp") -> "cpp"
            else -> "text"
        }
    }

    private fun extractProjectName(userRequest: String): String? {
        // Try to extract a meaningful project name from the user's request
        val cleanedRequest = userRequest.lowercase()
            .replace(Regex("(create|build|make|develop|generate)\\s+(a\\s+|an\\s+)?"), "")
            .replace(Regex("[^a-z0-9\\s]"), "")
            .trim()
            .split(" ")
            .take(3)
            .joinToString("_")
            .replace(Regex("\\s+"), "_")

        return if (cleanedRequest.length >= 3) {
            cleanedRequest.replaceFirstChar { it.uppercase() }
        } else null
    }

    /**
     * Save generated code files to device storage.
     */
    private fun saveCodeToStorage(projectName: String, files: List<GeneratedFile>): File {
        val baseDir = File(
            context.getExternalFilesDir(null),
            "${Constants.CODE_OUTPUT_DIR}/$projectName"
        )
        baseDir.mkdirs()

        for (file in files) {
            val outputFile = File(baseDir, file.relativePath)
            outputFile.parentFile?.mkdirs()
            outputFile.writeText(file.content)
        }

        Log.i(TAG, "Saved ${files.size} files to ${baseDir.absolutePath}")
        return baseDir
    }
}
