package com.arcle.intelligence.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.UiModeManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.util.Calendar

/**
 * Device Control Manager.
 * Handles all AUTO_OFFLINE device control commands:
 * flashlight, volume, Wi-Fi, Bluetooth, screenshot, dark mode,
 * battery saver, airplane mode, alarms, calculator, gallery.
 */
class DeviceControlManager(private val context: Context) {

    companion object {
        private const val TAG = "DeviceControlManager"
    }

    /**
     * Execute an offline device control command.
     * Returns a descriptive result string.
     */
    fun executeCommand(command: String): String {
        val cmd = command.lowercase().trim()

        return when {
            // Flashlight
            cmd.contains("flashlight") || cmd.contains("torch") -> {
                val turnOn = cmd.contains("on") || cmd.contains("turn on") || cmd.contains("enable")
                toggleFlashlight(turnOn)
            }

            // Volume
            cmd.contains("volume up") -> adjustVolume(AudioManager.ADJUST_RAISE)
            cmd.contains("volume down") -> adjustVolume(AudioManager.ADJUST_LOWER)
            cmd.contains("mute") || cmd.contains("silent") -> adjustVolume(AudioManager.ADJUST_MUTE)
            cmd.contains("unmute") -> adjustVolume(AudioManager.ADJUST_UNMUTE)
            cmd.contains("volume") -> {
                when {
                    cmd.contains("up") || cmd.contains("raise") || cmd.contains("higher") ->
                        adjustVolume(AudioManager.ADJUST_RAISE)
                    cmd.contains("down") || cmd.contains("lower") || cmd.contains("decrease") ->
                        adjustVolume(AudioManager.ADJUST_LOWER)
                    cmd.contains("max") || cmd.contains("full") || cmd.contains("maximum") ->
                        setMaxVolume()
                    else -> adjustVolume(AudioManager.ADJUST_RAISE)
                }
            }

            // Bluetooth
            cmd.contains("bluetooth") -> {
                val turnOn = cmd.contains("on") || cmd.contains("enable")
                toggleBluetooth(turnOn)
            }

            // Dark Mode
            cmd.contains("dark mode") || cmd.contains("night mode") -> {
                val turnOn = cmd.contains("on") || cmd.contains("enable") || cmd.contains("turn on")
                toggleDarkMode(turnOn)
            }

            // Battery Saver
            cmd.contains("battery saver") -> {
                openBatterySaverSettings()
            }

            // Airplane Mode
            cmd.contains("airplane") || cmd.contains("flight mode") -> {
                openAirplaneSettings()
            }

            // Alarm
            cmd.contains("alarm") || cmd.contains("set alarm") -> {
                val time = extractTimeFromCommand(cmd)
                setAlarm(time.first, time.second)
            }

            // Screenshot
            cmd.contains("screenshot") -> {
                takeScreenshot()
            }

            // Calculator
            cmd.contains("calculator") -> {
                openCalculator()
            }

            // Gallery
            cmd.contains("gallery") || cmd.contains("photos") -> {
                openGallery()
            }

            // Brightness
            cmd.contains("brightness") -> {
                when {
                    cmd.contains("up") || cmd.contains("increase") || cmd.contains("higher") ->
                        adjustBrightness(true)
                    cmd.contains("down") || cmd.contains("decrease") || cmd.contains("lower") ->
                        adjustBrightness(false)
                    cmd.contains("max") || cmd.contains("full") ->
                        setMaxBrightness()
                    else -> adjustBrightness(true)
                }
            }

            // Wi-Fi
            cmd.contains("wifi") || cmd.contains("wi-fi") -> {
                val turnOn = cmd.contains("on") || cmd.contains("enable")
                toggleWifi(turnOn)
            }

            // Do Not Disturb
            cmd.contains("do not disturb") || cmd.contains("dnd") -> {
                openDndSettings()
            }

            else -> "I'm not sure how to handle that device command, Sir."
        }
    }

    private fun toggleFlashlight(on: Boolean): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, on)
            if (on) "Flashlight is on, Sir." else "Flashlight is off, Sir."
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling flashlight", e)
            "I couldn't control the flashlight, Sir."
        }
    }

    private fun adjustVolume(direction: Int): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
            )
            when (direction) {
                AudioManager.ADJUST_RAISE -> "Volume increased, Sir."
                AudioManager.ADJUST_LOWER -> "Volume decreased, Sir."
                AudioManager.ADJUST_MUTE -> "Audio muted, Sir."
                AudioManager.ADJUST_UNMUTE -> "Audio unmuted, Sir."
                else -> "Volume adjusted, Sir."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adjusting volume", e)
            "I couldn't adjust the volume, Sir."
        }
    }

    private fun setMaxVolume(): String {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, AudioManager.FLAG_SHOW_UI)
            "Volume set to maximum, Sir."
        } catch (e: Exception) {
            "I couldn't set the volume, Sir."
        }
    }

    @Suppress("DEPRECATION")
    private fun toggleBluetooth(on: Boolean): String {
        return try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter == null) {
                return "This device doesn't support Bluetooth, Sir."
            }
            if (on) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    bluetoothAdapter.enable()
                } else {
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                "Bluetooth is enabled, Sir."
            } else {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    bluetoothAdapter.disable()
                } else {
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                "Bluetooth is disabled, Sir."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling Bluetooth", e)
            "I couldn't control Bluetooth, Sir."
        }
    }

    private fun toggleDarkMode(on: Boolean): String {
        return try {
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            if (on) {
                uiModeManager.nightMode = UiModeManager.MODE_NIGHT_YES
                "Dark mode enabled, Sir."
            } else {
                uiModeManager.nightMode = UiModeManager.MODE_NIGHT_NO
                "Dark mode disabled, Sir."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling dark mode", e)
            "I couldn't change the display mode, Sir."
        }
    }

    private fun openBatterySaverSettings(): String {
        return try {
            val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Opening battery saver settings, Sir."
        } catch (e: Exception) {
            "I couldn't open battery settings, Sir."
        }
    }

    private fun openAirplaneSettings(): String {
        return try {
            val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Opening airplane mode settings, Sir."
        } catch (e: Exception) {
            "I couldn't open airplane settings, Sir."
        }
    }

    private fun setAlarm(hour: Int, minute: Int): String {
        return try {
            val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val amPm = if (hour >= 12) "PM" else "AM"
            val displayHour = if (hour > 12) hour - 12 else if (hour == 0) 12 else hour
            "Alarm set for $displayHour:${String.format("%02d", minute)} $amPm, Sir."
        } catch (e: Exception) {
            Log.e(TAG, "Error setting alarm", e)
            "I couldn't set the alarm, Sir."
        }
    }

    private fun extractTimeFromCommand(cmd: String): Pair<Int, Int> {
        // Try to extract time from command like "6:30 AM" or "6:30" or "6 AM"
        val timeMatch = Regex("""(\d{1,2})[:\.](\d{2})\s*(am|pm)?""", RegexOption.IGNORE_CASE).find(cmd)
        if (timeMatch != null) {
            var hour = timeMatch.groupValues[1].toInt()
            val minute = timeMatch.groupValues[2].toInt()
            val period = timeMatch.groupValues[3].lowercase()
            if (period == "pm" && hour < 12) hour += 12
            if (period == "am" && hour == 12) hour = 0
            return Pair(hour, minute)
        }

        val hourOnlyMatch = Regex("""(\d{1,2})\s*(am|pm)""", RegexOption.IGNORE_CASE).find(cmd)
        if (hourOnlyMatch != null) {
            var hour = hourOnlyMatch.groupValues[1].toInt()
            val period = hourOnlyMatch.groupValues[2].lowercase()
            if (period == "pm" && hour < 12) hour += 12
            if (period == "am" && hour == 12) hour = 0
            return Pair(hour, 0)
        }

        // Default to 7:00 AM if no time found
        return Pair(7, 0)
    }

    private fun takeScreenshot(): String {
        return "Taking a screenshot requires the accessibility service, Sir. One moment."
    }

    private fun openCalculator(): String {
        return try {
            val intent = Intent().apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_APP_CALCULATOR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opening calculator, Sir."
        } catch (e: Exception) {
            "I couldn't open the calculator, Sir."
        }
    }

    private fun openGallery(): String {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                type = "image/*"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opening gallery, Sir."
        } catch (e: Exception) {
            "I couldn't open the gallery, Sir."
        }
    }

    private fun adjustBrightness(increase: Boolean): String {
        return try {
            val current = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128
            )
            val newBrightness = if (increase) {
                (current + 50).coerceAtMost(255)
            } else {
                (current - 50).coerceAtLeast(0)
            }
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                newBrightness
            )
            if (increase) "Brightness increased, Sir." else "Brightness decreased, Sir."
        } catch (e: Exception) {
            "I couldn't adjust the brightness, Sir. I may need permission."
        }
    }

    private fun setMaxBrightness(): String {
        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                255
            )
            "Brightness set to maximum, Sir."
        } catch (e: Exception) {
            "I couldn't set the brightness, Sir."
        }
    }

    @Suppress("DEPRECATION")
    private fun toggleWifi(on: Boolean): String {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                wifiManager.isWifiEnabled = on
                if (on) "Wi-Fi enabled, Sir." else "Wi-Fi disabled, Sir."
            } else {
                val intent = Intent(Settings.Panel.ACTION_WIFI)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Opening Wi-Fi settings, Sir."
            }
        } catch (e: Exception) {
            "I couldn't control Wi-Fi, Sir."
        }
    }

    private fun openDndSettings(): String {
        return try {
            val intent = Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Opening Do Not Disturb settings, Sir."
        } catch (e: Exception) {
            "I couldn't open DND settings, Sir."
        }
    }
}
