package com.arcle.intelligence.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Arcle Accessibility Service — enables UI automation, web content extraction, and Deep Research.
 * Required for extracting text from Chrome for search results and weather data.
 */
class ArcleAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ArcleAccessibilityService"
        var instance: ArcleAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }

        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events are processed on-demand via static methods
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Extract all visible text from the current screen.
     * Used by DeepResearchEngine to get content from Chrome pages.
     */
    fun extractScreenText(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val textBuilder = StringBuilder()
        traverseAndExtractText(rootNode, textBuilder)
        rootNode.recycle()
        return textBuilder.toString()
    }

    /**
     * Extract page title from Chrome's URL bar or title bar.
     */
    fun extractPageTitle(): String {
        val rootNode = rootInActiveWindow ?: return "Untitled"
        val title = findNodeText(rootNode, "com.android.chrome:id/url_bar")
            ?: findNodeText(rootNode, "com.android.chrome:id/title_bar")
            ?: "Untitled"
        rootNode.recycle()
        return title
    }

    /**
     * Check if Chrome has finished loading (progress bar gone).
     */
    fun isPageLoaded(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val progressBar = findNodeById(rootNode, "com.android.chrome:id/progress")
        val loaded = progressBar == null // If progress bar is gone, page is loaded
        rootNode.recycle()
        return loaded
    }

    private fun traverseAndExtractText(node: AccessibilityNodeInfo, builder: StringBuilder) {
        node.text?.let {
            if (it.isNotBlank()) {
                builder.append(it).append(" ")
            }
        }
        node.contentDescription?.let {
            if (it.isNotBlank() && node.text == null) {
                builder.append(it).append(" ")
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseAndExtractText(child, builder)
            child.recycle()
        }
    }

    private fun findNodeText(root: AccessibilityNodeInfo, viewId: String): String? {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        return if (nodes.isNotEmpty()) {
            val text = nodes[0].text?.toString()
            nodes.forEach { it.recycle() }
            text
        } else null
    }

    private fun findNodeById(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        return if (nodes.isNotEmpty()) nodes[0] else null
    }
}
