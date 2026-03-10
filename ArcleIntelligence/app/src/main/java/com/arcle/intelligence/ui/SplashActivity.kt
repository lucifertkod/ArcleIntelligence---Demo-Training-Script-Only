package com.arcle.intelligence.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.arcle.intelligence.utils.Constants
import kotlinx.coroutines.*

/**
 * Splash Screen — displays brand animation for 2.5 seconds,
 * then routes to PermissionsActivity or MainActivity.
 */
class SplashActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full screen, edge-to-edge
        window.statusBarColor = android.graphics.Color.parseColor("#0A0A0C")
        window.navigationBarColor = android.graphics.Color.parseColor("#0A0A0C")

        // Build splash UI programmatically
        val rootLayout = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0C"))
        }

        val titleText = TextView(this).apply {
            text = Constants.APP_NAME
            setTextColor(android.graphics.Color.WHITE)
            textSize = 42f
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            alpha = 0f
            translationY = 60f
        }

        val subtitleText = TextView(this).apply {
            text = "by ${Constants.CREATOR_NAME}"
            setTextColor(android.graphics.Color.parseColor("#6E56CF"))
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            alpha = 0f
            translationY = 40f
        }

        val versionText = TextView(this).apply {
            text = "v${Constants.APP_VERSION}"
            setTextColor(android.graphics.Color.parseColor("#FFFFFF40"))
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            alpha = 0f
        }

        val centerLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            val params = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            layoutParams = params
            addView(titleText)
            addView(subtitleText, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 })
            addView(versionText, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 32 })
        }

        rootLayout.addView(centerLayout)
        setContentView(rootLayout)

        // Animate
        scope.launch {
            delay(300)

            ObjectAnimator.ofFloat(titleText, "alpha", 0f, 1f).apply {
                duration = 800
                interpolator = OvershootInterpolator(1.5f)
            }.start()
            ObjectAnimator.ofFloat(titleText, "translationY", 60f, 0f).apply {
                duration = 800
                interpolator = OvershootInterpolator(1.5f)
            }.start()

            delay(400)

            ObjectAnimator.ofFloat(subtitleText, "alpha", 0f, 1f).apply { duration = 600 }.start()
            ObjectAnimator.ofFloat(subtitleText, "translationY", 40f, 0f).apply { duration = 600 }.start()

            delay(300)

            ObjectAnimator.ofFloat(versionText, "alpha", 0f, 0.4f).apply { duration = 500 }.start()

            // Wait and route
            delay(1500)
            routeToNextScreen()
        }
    }

    private fun routeToNextScreen() {
        val allPermissionsGranted = hasAllRequiredPermissions()
        val nextActivity = if (allPermissionsGranted) {
            MainActivity::class.java
        } else {
            PermissionsActivity::class.java
        }
        startActivity(Intent(this, nextActivity))
        finish()
    }

    private fun hasAllRequiredPermissions(): Boolean {
        val requiredPermissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
