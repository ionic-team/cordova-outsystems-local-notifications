package com.outsystems.plugins.localnotifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import java.util.Date

/**
 * Re-schedules persisted notifications after a device reboot. Ported from the
 * Capacitor plugin (CapConfig replaced by an empty config map).
 */
class LocalNotificationRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val um = context.getSystemService(UserManager::class.java)
        if (um == null || !um.isUserUnlocked) return

        val storage = NotificationStorage(context)
        val ids = storage.getSavedNotificationIds()

        val notifications = ArrayList<LocalNotification>(ids.size)
        val updatedNotifications = ArrayList<LocalNotification>()
        for (id in ids) {
            val notification = storage.getSavedNotification(id) ?: continue

            val schedule = notification.schedule
            if (schedule != null) {
                val at = schedule.at
                if (at != null && at.before(Date())) {
                    val newDateTime = Date().time + 15 * 1000
                    schedule.at = Date(newDateTime)
                    notification.schedule = schedule
                    updatedNotifications.add(notification)
                }
            }

            notifications.add(notification)
        }

        if (updatedNotifications.isNotEmpty()) {
            storage.appendNotifications(updatedNotifications)
        }

        val manager = LocalNotificationManager(storage, null, context, emptyMap())
        try {
            manager.schedule(notifications)
        } catch (ignored: LocalNotificationsException) {
            // Notifications disabled on reboot — nothing to restore.
        }
    }
}
