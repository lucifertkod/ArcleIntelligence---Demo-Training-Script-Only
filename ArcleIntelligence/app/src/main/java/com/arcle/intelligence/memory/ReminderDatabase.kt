package com.arcle.intelligence.memory

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.room.*
import androidx.work.*
import com.arcle.intelligence.services.ReminderAlarmReceiver
import java.util.concurrent.TimeUnit

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val message: String,
    val triggerTime: Long,
    val isRepeating: Boolean,
    val repeatIntervalMs: Long,
    val isTriggered: Boolean = false
)

@Dao
interface ReminderDao {
    @Insert
    suspend fun insertReminder(reminder: Reminder): Long

    @Query("SELECT * FROM reminders WHERE isTriggered = 0 ORDER BY triggerTime ASC")
    suspend fun getPendingReminders(): List<Reminder>

    @Query("SELECT * FROM reminders ORDER BY triggerTime DESC")
    suspend fun getAllReminders(): List<Reminder>

    @Query("UPDATE reminders SET isTriggered = 1 WHERE id = :reminderId")
    suspend fun markTriggered(reminderId: Int)

    @Query("DELETE FROM reminders WHERE id = :reminderId")
    suspend fun deleteReminder(reminderId: Int)

    @Query("DELETE FROM reminders")
    suspend fun deleteAllReminders()
}

@Database(entities = [Reminder::class], version = 1, exportSchema = false)
abstract class ReminderDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao

    companion object {
        @Volatile
        private var INSTANCE: ReminderDatabase? = null

        fun getInstance(context: Context): ReminderDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ReminderDatabase::class.java,
                    "arcle_reminder_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class ReminderManager(private val context: Context) {

    companion object {
        private const val TAG = "ReminderManager"
    }

    private val dao = ReminderDatabase.getInstance(context).reminderDao()

    suspend fun setReminder(message: String, triggerTimeMs: Long, isRepeating: Boolean = false, intervalMs: Long = 0): String {
        val reminder = Reminder(
            message = message,
            triggerTime = triggerTimeMs,
            isRepeating = isRepeating,
            repeatIntervalMs = intervalMs
        )
        val id = dao.insertReminder(reminder).toInt()

        if (isRepeating) {
            scheduleRepeatingAlarm(id, message, triggerTimeMs, intervalMs)
        } else {
            scheduleOneTimeReminder(id, message, triggerTimeMs)
        }

        val timeStr = formatTime(triggerTimeMs)
        return if (isRepeating) {
            "Got it, Sir. I'll remind you to $message every ${formatInterval(intervalMs)}, starting at $timeStr."
        } else {
            "Got it, Sir. I'll remind you to $message at $timeStr."
        }
    }

    private fun scheduleOneTimeReminder(id: Int, message: String, triggerTimeMs: Long) {
        val delay = triggerTimeMs - System.currentTimeMillis()
        if (delay <= 0) return

        val workRequest = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(
                "reminder_id" to id,
                "reminder_message" to message
            ))
            .addTag("reminder_$id")
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)
    }

    @Suppress("DEPRECATION")
    private fun scheduleRepeatingAlarm(id: Int, message: String, firstTrigger: Long, intervalMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            putExtra("reminder_id", id)
            putExtra("reminder_message", message)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            firstTrigger,
            intervalMs,
            pendingIntent
        )
    }

    suspend fun checkPendingReminders(): List<Reminder> {
        return dao.getPendingReminders()
    }

    suspend fun getPendingRemindersMessage(): String? {
        val pending = dao.getPendingReminders()
        if (pending.isEmpty()) return null

        val reminderList = pending.joinToString(". ") { "\"${it.message}\" at ${formatTime(it.triggerTime)}" }
        return "Sir, you have ${pending.size} pending reminder${if (pending.size > 1) "s" else ""}: $reminderList."
    }

    fun parseTimeString(timeStr: String): Long {
        // Parse common time formats and convert to epoch milliseconds
        val now = java.util.Calendar.getInstance()

        // "every X hours/minutes"
        val everyMatch = Regex("""every\s+(\d+)\s*(hour|minute|min|hr)""", RegexOption.IGNORE_CASE).find(timeStr)
        if (everyMatch != null) {
            val amount = everyMatch.groupValues[1].toLong()
            val unit = everyMatch.groupValues[2].lowercase()
            val intervalMs = when {
                unit.startsWith("hour") || unit.startsWith("hr") -> amount * 3600 * 1000
                unit.startsWith("min") -> amount * 60 * 1000
                else -> amount * 3600 * 1000
            }
            return intervalMs // Return interval for repeating reminders
        }

        // "X:XX AM/PM"
        val timeMatch = Regex("""(\d{1,2})[:\.](\d{2})\s*(am|pm)?""", RegexOption.IGNORE_CASE).find(timeStr)
        if (timeMatch != null) {
            var hour = timeMatch.groupValues[1].toInt()
            val minute = timeMatch.groupValues[2].toInt()
            val period = timeMatch.groupValues[3].lowercase()
            if (period == "pm" && hour < 12) hour += 12
            if (period == "am" && hour == 12) hour = 0

            now.set(java.util.Calendar.HOUR_OF_DAY, hour)
            now.set(java.util.Calendar.MINUTE, minute)
            now.set(java.util.Calendar.SECOND, 0)
            if (now.timeInMillis < System.currentTimeMillis()) {
                now.add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
            return now.timeInMillis
        }

        // "in X minutes/hours"
        val inMatch = Regex("""in\s+(\d+)\s*(minute|min|hour|hr)""", RegexOption.IGNORE_CASE).find(timeStr)
        if (inMatch != null) {
            val amount = inMatch.groupValues[1].toLong()
            val unit = inMatch.groupValues[2].lowercase()
            val ms = when {
                unit.startsWith("min") -> amount * 60 * 1000
                unit.startsWith("hour") || unit.startsWith("hr") -> amount * 3600 * 1000
                else -> amount * 60 * 1000
            }
            return System.currentTimeMillis() + ms
        }

        // Default: 1 hour from now
        return System.currentTimeMillis() + 3600 * 1000
    }

    private fun formatTime(timeMs: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timeMs }
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)
        val amPm = if (hour >= 12) "PM" else "AM"
        val displayHour = if (hour > 12) hour - 12 else if (hour == 0) 12 else hour
        return "$displayHour:${String.format("%02d", minute)} $amPm"
    }

    private fun formatInterval(ms: Long): String {
        val hours = ms / (3600 * 1000)
        val minutes = (ms % (3600 * 1000)) / (60 * 1000)
        return when {
            hours > 0 && minutes > 0 -> "$hours hour${if (hours > 1) "s" else ""} and $minutes minute${if (minutes > 1) "s" else ""}"
            hours > 0 -> "$hours hour${if (hours > 1) "s" else ""}"
            else -> "$minutes minute${if (minutes > 1) "s" else ""}"
        }
    }
}

class ReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getInt("reminder_id", -1)
        val message = inputData.getString("reminder_message") ?: "Reminder"

        // Mark as triggered in database
        val dao = ReminderDatabase.getInstance(applicationContext).reminderDao()
        if (id > 0) dao.markTriggered(id)

        // Show notification
        showReminderNotification(message)

        return Result.success()
    }

    private fun showReminderNotification(message: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "arcle_reminders", "Reminders",
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, "arcle_reminders")
            .setContentTitle("Arcle Reminder")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
