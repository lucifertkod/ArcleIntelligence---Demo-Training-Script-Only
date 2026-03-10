package com.arcle.intelligence.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import com.arcle.intelligence.memory.ChatDatabase
import com.arcle.intelligence.memory.ReminderDatabase
import com.arcle.intelligence.telemetry.TelemetryBatchManager
import com.arcle.intelligence.ArcleApplication
import kotlinx.coroutines.*

/**
 * Settings Activity — preferences, wake word sensitivity, TTS speed,
 * memory management, and telemetry opt-out.
 */
class SettingsActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var telemetryManager: TelemetryBatchManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#0A0A0C")
        window.navigationBarColor = android.graphics.Color.parseColor("#0A0A0C")

        telemetryManager = (application as ArcleApplication).telemetryManager
        val prefs = getSharedPreferences("arcle_settings", MODE_PRIVATE)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0C"))
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
        }

        // Top bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val backBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { finish() }
        }
        topBar.addView(backBtn)

        val titleView = TextView(this).apply {
            text = "⚙ Settings"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 24f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = 16 }
            layoutParams = params
        }
        topBar.addView(titleView)
        layout.addView(topBar)

        addSpacer(layout, 32)

        // ─── Voice Settings ────────────────────────────────────────
        addSectionTitle(layout, "Voice Settings")

        // TTS Speed
        addSliderSetting(layout, "TTS Speed", 0.5f, 2.0f,
            prefs.getFloat("tts_speed", 1.0f)) { value ->
            prefs.edit().putFloat("tts_speed", value).apply()
        }

        // TTS Pitch
        addSliderSetting(layout, "TTS Pitch", 0.5f, 2.0f,
            prefs.getFloat("tts_pitch", 1.0f)) { value ->
            prefs.edit().putFloat("tts_pitch", value).apply()
        }

        // Wake Word Sensitivity
        addSliderSetting(layout, "Wake Word Sensitivity", 0.1f, 1.0f,
            prefs.getFloat("kws_sensitivity", 0.6f)) { value ->
            prefs.edit().putFloat("kws_sensitivity", value).apply()
        }

        addSpacer(layout, 24)

        // ─── Privacy ───────────────────────────────────────────────
        addSectionTitle(layout, "Privacy")

        // Telemetry Toggle
        addToggleSetting(layout, "Send anonymous usage data",
            "Helps improve Arcle Intelligence",
            telemetryManager.isTelemetryEnabled()) { enabled ->
            telemetryManager.setTelemetryEnabled(enabled)
        }

        addSpacer(layout, 24)

        // ─── Memory Management ─────────────────────────────────────
        addSectionTitle(layout, "Memory Management")

        // Chat count
        val chatCountView = TextView(this).apply {
            text = "Loading chat count..."
            setTextColor(android.graphics.Color.parseColor("#FFFFFF80"))
            textSize = 14f
            setPadding(0, 8, 0, 8)
        }
        layout.addView(chatCountView)

        scope.launch(Dispatchers.IO) {
            val count = ChatDatabase.getInstance(this@SettingsActivity).chatDao().getMessageCount()
            withContext(Dispatchers.Main) {
                chatCountView.text = "Chat history: $count messages"
            }
        }

        // Clear Chat History
        addDangerButton(layout, "Clear Chat History") {
            scope.launch(Dispatchers.IO) {
                ChatDatabase.getInstance(this@SettingsActivity).chatDao().deleteAllMessages()
                withContext(Dispatchers.Main) {
                    chatCountView.text = "Chat history: 0 messages"
                    Toast.makeText(this@SettingsActivity, "Chat history cleared", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Clear Reminders
        addDangerButton(layout, "Clear All Reminders") {
            scope.launch(Dispatchers.IO) {
                ReminderDatabase.getInstance(this@SettingsActivity).reminderDao().deleteAllReminders()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "Reminders cleared", Toast.LENGTH_SHORT).show()
                }
            }
        }

        addSpacer(layout, 24)

        // ─── About ─────────────────────────────────────────────────
        addSectionTitle(layout, "About")

        val aboutView = TextView(this).apply {
            text = "Arcle Intelligence v2.0.0\n" +
                   "Created by Abhinav Anand\n\n" +
                   "100% on-device AI — zero cloud inference.\n" +
                   "7 AI models • 27 intent types • Emotional intelligence\n\n" +
                   "All AI processing runs entirely on your device.\n" +
                   "Your conversations never leave your phone."
            setTextColor(android.graphics.Color.parseColor("#FFFFFFAA"))
            textSize = 14f
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
            setPadding(24, 24, 24, 24)
        }
        layout.addView(aboutView)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun addSectionTitle(parent: LinearLayout, title: String) {
        val tv = TextView(this).apply {
            text = title
            setTextColor(android.graphics.Color.parseColor("#6E56CF"))
            textSize = 18f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16; bottomMargin = 8 }
            layoutParams = params
        }
        parent.addView(tv)
    }

    private fun addSliderSetting(parent: LinearLayout, label: String, min: Float, max: Float,
                                  current: Float, onChange: (Float) -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
            setPadding(24, 16, 24, 16)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            layoutParams = params
        }

        val valueText = TextView(this).apply {
            text = "$label: ${String.format("%.1f", current)}"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
        }
        container.addView(valueText)

        val seekBar = SeekBar(this).apply {
            this.max = 100
            progress = ((current - min) / (max - min) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = min + (progress / 100f) * (max - min)
                    valueText.text = "$label: ${String.format("%.1f", value)}"
                    onChange(value)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        container.addView(seekBar)
        parent.addView(container)
    }

    private fun addToggleSetting(parent: LinearLayout, label: String, subtitle: String,
                                  isChecked: Boolean, onChange: (Boolean) -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
            setPadding(24, 16, 24, 16)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            layoutParams = params
        }

        val textLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = params
        }

        textLayout.addView(TextView(this).apply {
            text = label
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
        })
        textLayout.addView(TextView(this).apply {
            text = subtitle
            setTextColor(android.graphics.Color.parseColor("#FFFFFF60"))
            textSize = 12f
        })
        container.addView(textLayout)

        val toggle = Switch(this).apply {
            this.isChecked = isChecked
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        container.addView(toggle)
        parent.addView(container)
    }

    private fun addDangerButton(parent: LinearLayout, label: String, onClick: () -> Unit) {
        val btn = Button(this).apply {
            text = label
            setTextColor(android.graphics.Color.parseColor("#FF4444"))
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            layoutParams = params
            setPadding(0, 20, 0, 20)
            setOnClickListener {
                android.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Confirm")
                    .setMessage("Are you sure you want to $label?")
                    .setPositiveButton("Yes") { _, _ -> onClick() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        parent.addView(btn)
    }

    private fun addSpacer(parent: LinearLayout, height: Int) {
        parent.addView(android.view.View(this).apply {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height
            )
            layoutParams = params
        })
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
