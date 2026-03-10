package com.arcle.intelligence.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * First-launch Permissions Activity.
 * Requests all required permissions one by one.
 * Grants are required before proceeding to the main app.
 */
class PermissionsActivity : ComponentActivity() {

    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_EXTERNAL_STORAGE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        checkAndProceed()
    }

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.parseColor("#0A0A0C")
        window.navigationBarColor = android.graphics.Color.parseColor("#0A0A0C")

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(64, 128, 64, 128)
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0C"))
        }

        val titleText = TextView(this).apply {
            text = "Welcome to Arcle Intelligence"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 28f
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
        }
        rootLayout.addView(titleText)

        val descText = TextView(this).apply {
            text = "To deliver the best experience, Arcle needs a few permissions.\n\n" +
                   "• Microphone — for voice commands and wake word detection\n" +
                   "• Camera — for object recognition and image analysis\n" +
                   "• Contacts — for calling and messaging contacts by name\n" +
                   "• Phone — for making calls on your behalf\n" +
                   "• SMS — for sending text messages\n" +
                   "• Storage — for saving generated code, images, and videos"
            setTextColor(android.graphics.Color.parseColor("#FFFFFFCC"))
            textSize = 15f
            gravity = android.view.Gravity.START
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 48 }
            layoutParams = params
        }
        rootLayout.addView(descText)

        statusText = TextView(this).apply {
            text = ""
            setTextColor(android.graphics.Color.parseColor("#6E56CF"))
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 32 }
            layoutParams = params
        }
        rootLayout.addView(statusText)

        val grantButton = Button(this).apply {
            text = "Grant Permissions"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            setBackgroundColor(android.graphics.Color.parseColor("#6E56CF"))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 48 }
            layoutParams = params
            setPadding(0, 32, 0, 32)
            setOnClickListener { requestAllPermissions() }
        }
        rootLayout.addView(grantButton)

        setContentView(rootLayout)
    }

    private fun requestAllPermissions() {
        val notYetGranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notYetGranted.isEmpty()) {
            proceedToMain()
        } else {
            permissionLauncher.launch(notYetGranted.toTypedArray())
        }
    }

    private fun checkAndProceed() {
        val granted = requiredPermissions.count {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val total = requiredPermissions.size

        statusText.text = "$granted of $total permissions granted"

        if (granted == total) {
            proceedToMain()
        } else {
            statusText.text = "$granted of $total granted. Some permissions are required for full functionality."
        }
    }

    private fun proceedToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
