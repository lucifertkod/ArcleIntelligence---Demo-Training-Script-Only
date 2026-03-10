package com.arcle.intelligence.automation

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.arcle.intelligence.utils.ArcleResponses
import com.arcle.intelligence.utils.Constants
import kotlinx.coroutines.delay

/**
 * Internet App Manager.
 * Implements the complete Internet Logic Flow for AUTO_NET and DEEP_RESEARCH intents.
 */
class InternetAppManager(private val context: Context) {

    companion object {
        private const val TAG = "InternetAppManager"
    }

    interface InternetFlowListener {
        fun onSpeak(text: String)
        fun onAppLaunched(appName: String)
        fun onConnectionFailed()
    }

    var listener: InternetFlowListener? = null

    /**
     * Execute the full Internet Logic Flow as specified in Task 7.
     */
    suspend fun executeInternetFlow(appName: String, packageName: String): Boolean {
        // Step 1 — Check Network
        if (isInternetAvailable()) {
            // Step 2 — Internet Available
            listener?.onSpeak("Opening $appName now, Sir.")
            launchApp(packageName, appName)
            listener?.onAppLaunched(appName)
            return true
        }

        // Step 3 — No Internet
        enableInternet()
        listener?.onSpeak(ArcleResponses.random(ArcleResponses.CONNECTING_PHRASES))

        // Step 4 — Wait for Connection
        val connected = waitForConnection()
        if (connected) {
            listener?.onSpeak(ArcleResponses.random(ArcleResponses.INTERNET_SUCCESS_PHRASES))
            launchApp(packageName, appName)
            listener?.onAppLaunched(appName)
            return true
        } else {
            listener?.onSpeak(
                "I wasn't able to connect to the internet, Sir. " +
                "Please enable Wi-Fi or mobile data manually and try again."
            )
            listener?.onConnectionFailed()
            return false
        }
    }

    /**
     * Check for internet — just the availability check without launching apps.
     * Used by DeepResearchEngine before starting research.
     */
    suspend fun ensureInternetAvailable(): Boolean {
        if (isInternetAvailable()) return true

        enableInternet()
        listener?.onSpeak(ArcleResponses.random(ArcleResponses.CONNECTING_PHRASES))

        return waitForConnection()
    }

    fun isInternetAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    @Suppress("DEPRECATION")
    private fun enableInternet() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Android < Q: programmatically enable Wi-Fi
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiManager.isWifiEnabled = true
            } else {
                // Android Q+: launch connectivity panel
                val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling internet", e)
            // Fallback: open Wi-Fi settings
            try {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Error opening Wi-Fi settings", e2)
            }
        }
    }

    private suspend fun waitForConnection(): Boolean {
        val maxWaitMs = Constants.INTERNET_WAIT_TIMEOUT_MS
        val pollInterval = Constants.INTERNET_POLL_INTERVAL_MS
        var elapsed = 0L

        while (elapsed < maxWaitMs) {
            delay(pollInterval)
            elapsed += pollInterval

            if (isInternetAvailable()) {
                return true
            }
        }
        return false
    }

    private fun launchApp(packageName: String, appName: String) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            } else {
                // App not installed — open Play Store
                val playStoreIntent = Intent(
                    Intent.ACTION_VIEW,
                    android.net.Uri.parse("market://details?id=$packageName")
                )
                playStoreIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(playStoreIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching $appName ($packageName)", e)
        }
    }
}
