package com.outsystems.plugins.localnotifications

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import org.json.JSONObject

/**
 * Presents a scheduled notification when its alarm fires, and reschedules cron
 * ("on") notifications. Registered as a broadcast receiver in plugin.xml.
 */
class TimedNotificationPublisher : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        val notification: Notification? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NOTIFICATION_KEY, Notification::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NOTIFICATION_KEY)
        }
        if (notification == null) return

        notification.`when` = System.currentTimeMillis()

        val id = intent.getIntExtra(LocalNotificationManager.NOTIFICATION_INTENT_KEY, Int.MIN_VALUE)
        if (id == Int.MIN_VALUE) {
            Log.e(LOG_TAG, "No valid id supplied")
        }
        val storage = NotificationStorage(context)
        val notificationJson = storage.getSavedNotificationAsJSObject(id.toString())
        OSLocalNotificationsPlugin.fireReceived(notificationJson)
        notificationManager.notify(id, notification)
        if (!rescheduleNotificationIfNeeded(context, intent, id)) {
            // Keep recurring (every / repeats) notifications in storage so cancel()/cancelAll()
            // can still find and cancel their OS repeating alarm. One-shot notifications are removed.
            if (!isRepeating(notificationJson)) {
                storage.deleteNotification(id.toString())
            }
        }
    }

    private fun isRepeating(notificationJson: JSONObject?): Boolean {
        val schedule = notificationJson?.optJSONObject("schedule") ?: return false
        if (schedule.optString("every", "").isNotEmpty()) return true
        return schedule.optBoolean("repeats", false)
    }

    private fun rescheduleNotificationIfNeeded(context: Context, intent: Intent, id: Int): Boolean {
        val dateString = intent.getStringExtra(CRON_KEY) ?: return false

        val date = DateMatch.fromMatchString(dateString)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val trigger = date.nextTrigger(Date())
        val clone = intent.clone() as Intent
        var flags = PendingIntent.FLAG_CANCEL_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        val pendingIntent = PendingIntent.getBroadcast(context, id, clone, flags)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(LOG_TAG, "Exact alarms not allowed in user settings. Notification scheduled with non-exact alarm.")
            alarmManager.set(AlarmManager.RTC, trigger, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC, trigger, pendingIntent)
        }
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss")
        Log.d(LOG_TAG, "notification " + id + " will next fire at " + sdf.format(Date(trigger)))
        return true
    }

    companion object {
        const val NOTIFICATION_KEY = "NotificationPublisher.notification"
        const val CRON_KEY = "NotificationPublisher.cron"
        private const val LOG_TAG = "OSLocalNotifications"
    }
}
