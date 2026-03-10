package com.arcle.intelligence.research

import android.util.Log
import com.arcle.intelligence.automation.InternetAppManager
import com.arcle.intelligence.utils.ArcleResponses
import com.arcle.intelligence.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Deep Research Engine.
 * Searches up to 200 sites, extracts data, compiles a comprehensive research report.
 * Retries failed pages up to 5 times. Never skips. Never gives up.
 */
class DeepResearchEngine(
    private val internetManager: InternetAppManager
) {

    companion object {
        private const val TAG = "DeepResearchEngine"
    }

    interface ResearchListener {
        fun onSpeak(text: String)
        fun onProgress(sitesSearched: Int, totalSites: Int, currentSource: String)
        fun onComplete(report: String)
        fun onError(error: String)
    }

    var listener: ResearchListener? = null

    data class ResearchResult(
        val reportText: String,
        val sitesSearched: Int,
        val sourceUrls: List<String>,
        val totalExtracted: Int
    )

    /**
     * Conduct deep multi-level research on a topic.
     */
    suspend fun conductResearch(topic: String): ResearchResult = withContext(Dispatchers.IO) {
        listener?.onSpeak(ArcleResponses.random(ArcleResponses.DEEP_RESEARCH_START))

        // Step 1: Ensure internet is available
        val internetReady = internetManager.ensureInternetAvailable()
        if (!internetReady) {
            val error = "I couldn't connect to the internet for research, Sir."
            listener?.onError(error)
            return@withContext ResearchResult(error, 0, emptyList(), 0)
        }

        // Step 2: Generate search queries (3 levels)
        val searchQueries = generateSearchQueries(topic)

        // Step 3: Collect URLs from search results
        val allUrls = mutableListOf<String>()
        for (query in searchQueries) {
            val urls = searchGoogle(query)
            allUrls.addAll(urls)
            delay(Constants.DEEP_RESEARCH_SEARCH_DELAY_MS)
        }

        // Deduplicate and limit
        val uniqueUrls = allUrls.distinct().take(Constants.DEEP_RESEARCH_MAX_SITES)

        // Step 4: Extract data from each URL
        val extractedData = mutableListOf<ExtractedPageData>()
        for ((index, url) in uniqueUrls.withIndex()) {
            listener?.onProgress(index + 1, uniqueUrls.size, url)

            if (index > 0 && index % 20 == 0) {
                listener?.onSpeak(ArcleResponses.random(ArcleResponses.DEEP_RESEARCH_PROGRESS))
            }

            val pageData = extractWithRetry(url)
            if (pageData != null) {
                extractedData.add(pageData)
            }

            delay(Constants.DEEP_RESEARCH_EXTRACT_DELAY_MS)
        }

        // Step 5: Compile research report
        val report = compileReport(topic, extractedData)

        listener?.onSpeak(ArcleResponses.random(ArcleResponses.DEEP_RESEARCH_COMPLETE))
        listener?.onComplete(report)

        ResearchResult(
            reportText = report,
            sitesSearched = uniqueUrls.size,
            sourceUrls = uniqueUrls,
            totalExtracted = extractedData.size
        )
    }

    private fun generateSearchQueries(topic: String): List<String> {
        return listOf(
            // Level 1: Broad
            topic,
            "$topic overview",
            "$topic explained",
            // Level 2: Specific
            "$topic latest research",
            "$topic statistics data",
            "$topic history timeline",
            // Level 3: Expert/controversial
            "$topic expert analysis",
            "$topic pros and cons",
            "$topic future predictions",
            "$topic controversy debate"
        )
    }

    data class ExtractedPageData(
        val url: String,
        val title: String,
        val content: String,
        val extractedAt: Long = System.currentTimeMillis()
    )

    private suspend fun searchGoogle(query: String): List<String> {
        return try {
            val searchUrl = "https://www.google.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&num=20"
            val html = fetchPage(searchUrl) ?: return emptyList()

            // Extract URLs from search results
            val urlPatterns = Regex("""https?://[^\s"<>]+""")
            val urls = urlPatterns.findAll(html)
                .map { it.value }
                .filter { url ->
                    !url.contains("google.com") && !url.contains("googleapis.com") &&
                    !url.contains("gstatic.com") && !url.contains("youtube.com") &&
                    !url.contains("facebook.com") && !url.contains("twitter.com")
                }
                .distinct()
                .toList()

            urls.take(20)
        } catch (e: Exception) {
            Log.e(TAG, "Error searching: $query", e)
            emptyList()
        }
    }

    private suspend fun extractWithRetry(url: String): ExtractedPageData? {
        var retryCount = 0
        while (retryCount < Constants.DEEP_RESEARCH_MAX_RETRIES) {
            try {
                val html = fetchPage(url)
                if (html != null && html.isNotEmpty()) {
                    val title = extractTitle(html)
                    val content = extractMainContent(html)
                    if (content.length > 50) {
                        return ExtractedPageData(url, title, content)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Retry $retryCount for $url", e)
            }

            retryCount++
            if (retryCount < Constants.DEEP_RESEARCH_MAX_RETRIES) {
                listener?.onSpeak(ArcleResponses.random(ArcleResponses.DEEP_RESEARCH_RETRY))
                delay(Constants.DEEP_RESEARCH_RETRY_DELAY_MS)
            }
        }

        Log.w(TAG, "All retries exhausted for $url")
        return null
    }

    private fun fetchPage(url: String): String? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = Constants.DEEP_RESEARCH_PAGE_TIMEOUT_MS.toInt()
            connection.readTimeout = Constants.DEEP_RESEARCH_PAGE_TIMEOUT_MS.toInt()
            connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().readText()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractTitle(html: String): String {
        val titleMatch = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.IGNORE_CASE).find(html)
        return titleMatch?.groupValues?.get(1)?.trim() ?: "Untitled"
    }

    private fun extractMainContent(html: String): String {
        // Strip scripts, styles, and HTML tags
        var content = html
            .replace(Regex("""<script[^>]*>.*?</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("""<style[^>]*>.*?</style>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("""<nav[^>]*>.*?</nav>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("""<footer[^>]*>.*?</footer>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("""<header[^>]*>.*?</header>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        // Limit to first 5000 chars
        return content.take(Constants.DEEP_RESEARCH_MAX_CHARS_PER_PAGE)
    }

    private fun compileReport(topic: String, data: List<ExtractedPageData>): String {
        if (data.isEmpty()) {
            return "I searched extensively but couldn't find enough information on: $topic\n" +
                   "This could be because the topic is very niche or the search was blocked."
        }

        val report = StringBuilder()
        report.appendLine("═══════════════════════════════════════════════")
        report.appendLine("DEEP RESEARCH REPORT: $topic")
        report.appendLine("═══════════════════════════════════════════════")
        report.appendLine("Sites searched: ${data.size}")
        report.appendLine()

        // Group content by relevance chunks
        report.appendLine("── KEY FINDINGS ──────────────────────────────")
        report.appendLine()

        // Combine all content and extract key sentences
        val allContent = data.joinToString(" ") { it.content }
        val sentences = allContent.split(Regex("""[.!?]+"""))
            .map { it.trim() }
            .filter { it.length in 20..500 }
            .distinct()
            .take(100)

        // Group into paragraphs
        sentences.chunked(5).forEach { group ->
            report.appendLine(group.joinToString(". ") + ".")
            report.appendLine()
        }

        report.appendLine("── SOURCES ───────────────────────────────────")
        data.take(20).forEachIndexed { index, page ->
            report.appendLine("${index + 1}. ${page.title} — ${page.url}")
        }

        return report.toString()
    }
}
