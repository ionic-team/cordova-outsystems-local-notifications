package com.outsystems.plugins.localnotifications

import android.content.Context
import android.content.SharedPreferences
import java.text.ParseException
import org.json.JSONException
import org.json.JSONObject

/**
 * Persists scheduled notifications, ported from the Capacitor plugin. The
 * action-type storage (Capacitor-only) is intentionally omitted.
 */
class NotificationStorage(private val context: Context) {

    fun appendNotifications(localNotifications: List<LocalNotification>) {
        val editor = getStorage(NOTIFICATION_STORE_ID).edit()
        for (request in localNotifications) {
            if (request.isScheduled()) {
                val id = request.id ?: continue
                editor.putString(id.toString(), request.source)
            }
        }
        editor.apply()
    }

    fun getSavedNotificationIds(): List<String> {
        val all = getStorage(NOTIFICATION_STORE_ID).all
        return if (all != null) ArrayList(all.keys) else ArrayList()
    }

    fun getSavedNotifications(): List<LocalNotification> {
        val all = getStorage(NOTIFICATION_STORE_ID).all ?: return ArrayList()
        val notifications = ArrayList<LocalNotification>()
        for (key in all.keys) {
            val notificationString = all[key] as? String
            val jsNotification = getNotificationFromJSONString(notificationString)
            if (jsNotification != null) {
                try {
                    notifications.add(LocalNotification.buildNotificationFromJSObject(jsNotification))
                } catch (e: ParseException) {
                }
            }
        }
        return notifications
    }

    fun getNotificationFromJSONString(notificationString: String?): JSONObject? {
        if (notificationString == null) return null
        return try {
            JSONObject(notificationString)
        } catch (e: JSONException) {
            null
        }
    }

    fun getSavedNotificationAsJSObject(key: String): JSONObject? {
        val notificationString = try {
            getStorage(NOTIFICATION_STORE_ID).getString(key, null)
        } catch (e: ClassCastException) {
            return null
        } ?: return null
        return try {
            JSONObject(notificationString)
        } catch (e: JSONException) {
            null
        }
    }

    fun getSavedNotification(key: String): LocalNotification? {
        val jsNotification = getSavedNotificationAsJSObject(key) ?: return null
        return try {
            LocalNotification.buildNotificationFromJSObject(jsNotification)
        } catch (e: ParseException) {
            null
        }
    }

    fun deleteNotification(id: String) {
        val editor = getStorage(NOTIFICATION_STORE_ID).edit()
        editor.remove(id)
        editor.apply()
    }

    private fun getStorage(key: String): SharedPreferences =
        context.getSharedPreferences(key, Context.MODE_PRIVATE)

    companion object {
        private const val NOTIFICATION_STORE_ID = "NOTIFICATION_STORE"
    }
}
