package com.arcle.intelligence.automation

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.util.Log

/**
 * System Info Manager — handles AUTO_SYSTEM intents.
 * Provides device information: battery, storage, RAM.
 */
class SystemInfoManager(private val context: Context) {

    fun getSystemInfo(query: String): String {
        val q = query.lowercase()
        return when {
            q.contains("battery") -> getBatteryInfo()
            q.contains("storage") || q.contains("space") -> getStorageInfo()
            q.contains("ram") || q.contains("memory") -> getRamInfo()
            else -> getAllSystemInfo()
        }
    }

    fun getBatteryInfo(): String {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        val isCharging = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
            BatteryManager.BATTERY_STATUS_CHARGING

        val chargingStr = if (isCharging) "and charging" else "and not charging"
        return "Your battery is at $level% $chargingStr, Sir."
    }

    fun getStorageInfo(): String {
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalBytes = stat.totalBytes
        val availableBytes = stat.availableBytes
        val usedBytes = totalBytes - availableBytes

        val totalGb = String.format("%.1f", totalBytes / (1024.0 * 1024.0 * 1024.0))
        val availableGb = String.format("%.1f", availableBytes / (1024.0 * 1024.0 * 1024.0))
        val usedGb = String.format("%.1f", usedBytes / (1024.0 * 1024.0 * 1024.0))

        return "Storage: $usedGb GB used of $totalGb GB total. $availableGb GB available, Sir."
    }

    fun getRamInfo(): String {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val totalRam = String.format("%.1f", memInfo.totalMem / (1024.0 * 1024.0 * 1024.0))
        val availableRam = String.format("%.1f", memInfo.availMem / (1024.0 * 1024.0 * 1024.0))
        val usedRam = String.format("%.1f", (memInfo.totalMem - memInfo.availMem) / (1024.0 * 1024.0 * 1024.0))

        return "RAM: $usedRam GB used of $totalRam GB total. $availableRam GB available, Sir."
    }

    private fun getAllSystemInfo(): String {
        return "${getBatteryInfo()}\n${getStorageInfo()}\n${getRamInfo()}"
    }
}
