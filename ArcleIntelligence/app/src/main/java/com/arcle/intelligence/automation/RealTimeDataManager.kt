package com.arcle.intelligence.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Real-Time Data Manager — handles Weather and Web Search via AccessibilityService.
 */
class RealTimeDataManager(private val context: Context) {

    companion object {
        private const val TAG = "RealTimeDataManager"
    }

    interface DataListener {
        fun onSpeak(text: String)
        fun onDataExtracted(data: String)
    }

    var listener: DataListener? = null

    fun getWeather(): String {
        listener?.onSpeak("Checking the weather for you, Sir.")
        return try {
            // Open built-in weather app or Google weather search
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=weather"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Searching for weather information now, Sir. I'll read the results for you."
        } catch (e: Exception) {
            Log.e(TAG, "Error getting weather", e)
            "I couldn't check the weather right now, Sir."
        }
    }

    fun performWebSearch(query: String): String {
        listener?.onSpeak("Searching for that information now, Sir.")
        return try {
            val searchUrl = "https://www.google.com/search?q=${Uri.encode(query)}"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl))
            intent.setPackage("com.android.chrome")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Searching now, Sir. I'll extract the information for you."
        } catch (e: Exception) {
            // Fallback to default browser
            try {
                val searchUrl = "https://www.google.com/search?q=${Uri.encode(query)}"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Searching now using your browser, Sir."
            } catch (e2: Exception) {
                Log.e(TAG, "Error performing web search", e2)
                "I couldn't perform that search, Sir."
            }
        }
    }
}
