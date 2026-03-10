package com.arcle.intelligence.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Boot receiver to start KWS service at device boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, KwsService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}

/**
 * Receiver for repeating reminder alarms.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("reminder_id", -1)
        val message = intent.getStringExtra("reminder_message") ?: "Reminder"

        CoroutineScope(Dispatchers.IO).launch {
            val dao = com.arcle.intelligence.memory.ReminderDatabase.getInstance(context).reminderDao()
            if (id > 0) dao.markTriggered(id)
        }

        showReminderNotification(context, id, message)
    }

    private fun showReminderNotification(context: Context, id: Int, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "arcle_reminders", "Reminders",
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(context, "arcle_reminders")
            .setContentTitle("Arcle Reminder")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(id, notification)
    }
}
