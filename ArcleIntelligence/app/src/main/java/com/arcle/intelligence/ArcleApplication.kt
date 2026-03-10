package com.arcle.intelligence

import android.app.Application
import android.content.Intent
import android.util.Log
import com.arcle.intelligence.services.KwsService
import com.arcle.intelligence.telemetry.TelemetryBatchManager

class ArcleApplication : Application() {

    companion object {
        private const val TAG = "ArcleApplication"
        lateinit var instance: ArcleApplication
            private set
    }

    lateinit var telemetryManager: TelemetryBatchManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize telemetry
        telemetryManager = TelemetryBatchManager(this)
        telemetryManager.schedulePeriodic()

        Log.i(TAG, "Arcle Intelligence Application initialized")
    }
}
