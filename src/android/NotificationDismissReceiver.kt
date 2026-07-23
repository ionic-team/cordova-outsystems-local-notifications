package com.outsystems.plugins.localnotifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receiver called when a notification is dismissed by the user.
 */
class NotificationDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val intExtra = intent.getIntExtra(LocalNotificationManager.NOTIFICATION_INTENT_KEY, Int.MIN_VALUE)
        if (intExtra == Int.MIN_VALUE) {
            Log.e("OSLocalNotifications", "Invalid notification dismiss operation")
            return
        }
        val storage = NotificationStorage(context)
        val existing = storage.getSavedNotification(intExtra.toString())
        if (LocalNotificationManager.isSafeToForget(existing)) {
            storage.deleteNotification(intExtra.toString())
        }
    }
}
