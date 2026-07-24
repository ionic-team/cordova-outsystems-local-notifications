package com.outsystems.plugins.localnotifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import org.json.JSONException
import org.json.JSONObject

/**
 * One-time, silent reconciliation run at plugin startup, covering two sources
 * of notifications this plugin doesn't otherwise know about:
 *
 * 1. The old Cordova (katzer) plugin's own storage — a different schema in a
 *    differently-named SharedPreferences file. Entries are translated into
 *    this plugin's schema and (re)scheduled, then removed from katzer's
 *    store so this only ever runs once per entry.
 * 2. This plugin's own storage, but with no live alarm currently registered
 *    under its own receiver — the case where the app previously used the new
 *    Capacitor plugin (same storage key/schema by construction) and switched
 *    to this one. Those are simply re-armed; no schema translation needed.
 *
 * Both are best-effort and silent: a single bad entry is skipped rather than
 * aborting the rest, and nothing is surfaced to the app either way.
 */
internal object LegacyNotificationMigrator {

    private const val LOG_TAG = "OSLocalNotifications"

    // katzer's own SharedPreferences file names (Notification.java: PREF_KEY_ID / PREF_KEY_PID).
    private const val KATZER_PREF_ID = "NOTIFICATION_ID"
    private const val KATZER_PREF_PID = "NOTIFICATION_PID"

    fun run(context: Context, storage: NotificationStorage, manager: LocalNotificationManager) {
        try {
            migrateFromKatzer(context, storage, manager)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Legacy katzer migration failed, continuing without it", e)
        }
        try {
            reconcileOwnStorage(context, storage, manager)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Sibling-plugin reconciliation failed, continuing without it", e)
        }
    }

    private fun migrateFromKatzer(context: Context, storage: NotificationStorage, manager: LocalNotificationManager) {
        val prefs = context.getSharedPreferences(KATZER_PREF_ID, Context.MODE_PRIVATE)
        val all = prefs.all
        if (all.isEmpty()) return

        for ((key, value) in all) {
            val json = (value as? String)?.let { parseJsonOrNull(it) } ?: continue
            val notification = try {
                translateFromKatzer(json)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Skipping unreadable legacy notification $key", e)
                null
            }
            if (notification != null) {
                scheduleQuietly(storage, manager, notification)
            }
        }

        // Either migrated or deliberately dropped (e.g. a stale one-shot) —
        // never re-attempt katzer's data once it's been processed once.
        prefs.edit().clear().apply()
        context.getSharedPreferences(KATZER_PREF_PID, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * Translates one of katzer's persisted notification JSON objects into this
     * plugin's own schema. Returns null when there's nothing worth
     * (re)scheduling — including a deliberate drop of a one-shot `at` entry
     * whose time has already passed, since katzer keeps no separate
     * pending/delivered split and there's no reliable way to tell "missed"
     * from "already seen and dismissed long ago".
     */
    private fun translateFromKatzer(json: JSONObject): LocalNotification? {
        val id = json.integerOrNull("id") ?: return null

        val trigger = json.jsObject("trigger")
        val every = trigger?.stringOrNull("every")?.takeIf { it.isNotEmpty() }
        val atMillis = trigger?.let {
            if (it.has("at") && !it.isNull("at")) it.optLong("at", 0L) else null
        }?.takeIf { it > 0L }

        val schedule = JSONObject()
        when {
            every != null -> schedule.put("every", every)
            atMillis != null -> {
                if (atMillis <= System.currentTimeMillis()) {
                    return null
                }
                val sdf = SimpleDateFormat(LocalNotificationSchedule.JS_DATE_FORMAT)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                schedule.put("at", sdf.format(Date(atMillis)))
            }
            else -> return null // no trigger at all — nothing to re-schedule
        }

        val built = JSONObject()
        built.put("id", id)
        built.put("title", json.stringOr("title", " "))
        built.put("body", json.stringOr("text", ""))

        // katzer's own default for "no custom sound" is the boolean `true`
        // (play the platform default), not a string — only migrate an actual
        // sound resource string, never a boolean.
        val soundValue = json.opt("sound")
        if (soundValue is String && soundValue.isNotEmpty()) {
            built.put("sound", soundValue)
        }

        val badge = json.integerOrNull("badge")
        if (badge != null) {
            built.put("badge", badge)
        }

        if (json.has("data") && !json.isNull("data")) {
            built.put("extra", json.get("data"))
        }

        built.put("schedule", schedule)

        return LocalNotification.buildNotificationFromJSObject(built)
    }

    private fun reconcileOwnStorage(context: Context, storage: NotificationStorage, manager: LocalNotificationManager) {
        for (idStr in storage.getSavedNotificationIds()) {
            val id = idStr.toIntOrNull() ?: continue
            val n = storage.getSavedNotification(idStr) ?: continue
            val schedule = n.schedule ?: continue
            if (n.cancelled) continue
            // One-shot already fired — nothing to re-arm, it's meant to stay
            // exactly as already-triggered until dismissed.
            if (!schedule.isPerpetual() && n.isTriggered()) continue
            if (hasLiveAlarm(context, id)) continue
            scheduleQuietly(storage, manager, n)
        }
    }

    private fun hasLiveAlarm(context: Context, id: Int): Boolean {
        val intent = Intent(context, TimedNotificationPublisher::class.java)
        var flags = PendingIntent.FLAG_NO_CREATE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, id, intent, flags) != null
    }

    private fun scheduleQuietly(storage: NotificationStorage, manager: LocalNotificationManager, notification: LocalNotification) {
        try {
            manager.schedule(listOf(notification))
            storage.appendNotifications(listOf(notification))
        } catch (e: LocalNotificationsException) {
            Log.w(LOG_TAG, "Failed to (re)schedule migrated notification ${notification.id}", e)
        }
    }

    private fun parseJsonOrNull(raw: String): JSONObject? = try {
        JSONObject(raw)
    } catch (e: JSONException) {
        null
    }
}
