package com.outsystems.plugins.localnotifications

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local notification model, ported from the Capacitor plugin. Includes the
 * additive `badge` / `foreground` fields. Attachments and action types are part
 * of the Capacitor-only surface and are not modelled here.
 */
class LocalNotification {

    var title: String? = null
    var body: String? = null
    var largeBody: String? = null
    var summaryText: String? = null
    var id: Int? = null
    var sound: String? = null
    var iconColor: String? = null
    var actionTypeId: String? = null
    var group: String? = null
    var inboxList: List<String>? = null
    var groupSummary: Boolean = false
    var ongoing: Boolean = false
    var autoCancel: Boolean = true
    // Raw JSON value. `extra` is documented as `any`, not just an object.
    var extra: Any? = null
    var schedule: LocalNotificationSchedule? = null
    var channelId: String? = null
    var source: String? = null
    var badge: Int? = null
    var foreground: Boolean? = null

    /**
     * Internal bookkeeping only — never sent to or read from JS. Set when
     * cancel()/cancelAll() preserves a still-delivered notification's record
     * instead of deleting it, so classification and reboot-restore can tell
     * it's no longer actually scheduled, even though live OS signals like the
     * alarm registration don't survive a reboot to say so themselves.
     */
    var cancelled: Boolean = false

    // Icons are stored as their bare resource base name.
    var smallIcon: String? = null
        set(value) {
            field = AssetUtil.getResourceBaseName(value)
        }
    var largeIcon: String? = null
        set(value) {
            field = AssetUtil.getResourceBaseName(value)
        }

    fun resolveSound(context: Context, defaultSound: Int): String? {
        var resId = AssetUtil.RESOURCE_ID_ZERO_VALUE
        val name = AssetUtil.getResourceBaseName(sound)
        if (name != null) resId = AssetUtil.getResourceID(context, name, "raw")
        if (resId == AssetUtil.RESOURCE_ID_ZERO_VALUE) resId = defaultSound
        return if (resId != AssetUtil.RESOURCE_ID_ZERO_VALUE) {
            ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.packageName + "/" + resId
        } else {
            null
        }
    }

    fun resolveIconColor(globalColor: String?): String? = iconColor ?: globalColor

    fun resolveSmallIcon(context: Context, defaultIcon: Int): Int {
        var resId = AssetUtil.RESOURCE_ID_ZERO_VALUE
        smallIcon?.let { resId = AssetUtil.getResourceID(context, it, "drawable") }
        if (resId == AssetUtil.RESOURCE_ID_ZERO_VALUE) resId = defaultIcon
        return resId
    }

    fun resolveLargeIcon(context: Context): Bitmap? {
        largeIcon?.let {
            val resId = AssetUtil.getResourceID(context, it, "drawable")
            return BitmapFactory.decodeResource(context.resources, resId)
        }
        return null
    }

    fun isScheduled(): Boolean {
        val s = schedule ?: return false
        return s.on != null || s.at != null || s.every != null
    }

    /**
     * Whether this notification has already fired and won't fire again — a
     * one-shot `at` whose time has passed. Perpetual schedules (`every`/`on`/
     * `repeats`) are never "triggered"; they stay "scheduled" indefinitely.
     */
    fun isTriggered(): Boolean {
        val s = schedule ?: return false
        if (s.isPerpetual()) return false
        val at = s.at ?: return false
        return at.time <= Date().time
    }

    companion object {

        @Throws(LocalNotificationsException::class)
        fun buildNotificationList(notificationArray: JSONArray?): List<LocalNotification> {
            if (notificationArray == null) {
                throw LocalNotificationsError.INVALID_NOTIFICATIONS_ARRAY.toException()
            }
            val result = ArrayList<LocalNotification>(notificationArray.length())

            for (i in 0 until notificationArray.length()) {
                val jsonNotification = notificationArray.optJSONObject(i)
                    ?: throw LocalNotificationsError.INVALID_NOTIFICATION_FORMAT.toException()

                try {
                    val identifier = jsonNotification.getLong("id")
                    if (identifier > Int.MAX_VALUE || identifier < Int.MIN_VALUE) {
                        throw LocalNotificationsError.IDENTIFIER_NOT_INT.toException()
                    }
                } catch (e: org.json.JSONException) {
                    throw LocalNotificationsError.INVALID_JSON.toException()
                }

                try {
                    result.add(buildNotificationFromJSObject(jsonNotification))
                } catch (e: ParseException) {
                    throw LocalNotificationsError.INVALID_DATE_FORMAT.toException()
                }
            }
            return result
        }

        @Throws(ParseException::class)
        fun buildNotificationFromJSObject(jsonObject: JSONObject): LocalNotification {
            val n = LocalNotification()
            n.source = jsonObject.toString()
            n.id = jsonObject.integerOrNull("id")
            n.body = jsonObject.stringOrNull("body")
            n.largeBody = jsonObject.stringOrNull("largeBody")
            n.summaryText = jsonObject.stringOrNull("summaryText")
            n.actionTypeId = jsonObject.stringOrNull("actionTypeId")
            n.group = jsonObject.stringOrNull("group")
            n.sound = jsonObject.stringOrNull("sound")
            n.title = jsonObject.stringOrNull("title")
            n.smallIcon = jsonObject.stringOrNull("smallIcon")
            n.largeIcon = jsonObject.stringOrNull("largeIcon")
            n.iconColor = jsonObject.stringOrNull("iconColor")
            n.groupSummary = jsonObject.booleanOr("groupSummary", false)
            n.channelId = jsonObject.stringOrNull("channelId")
            val schedule = jsonObject.jsObject("schedule")
            if (schedule != null) {
                n.schedule = LocalNotificationSchedule(schedule)
            }
            // jsObject() only accepts an object and silently drops any other type
            // (e.g. a plain string), so read the raw value instead.
            n.extra = if (jsonObject.has("extra") && !jsonObject.isNull("extra")) jsonObject.get("extra") else null
            n.ongoing = jsonObject.booleanOr("ongoing", false)
            n.autoCancel = jsonObject.booleanOr("autoCancel", true)
            if (jsonObject.has("badge")) {
                n.badge = jsonObject.integerOrNull("badge")
            }
            if (jsonObject.has("foreground")) {
                n.foreground = jsonObject.booleanOr("foreground", false)
            }
            n.cancelled = jsonObject.booleanOr("cancelled", false)

            try {
                val inboxList = jsonObject.optJSONArray("inboxList")
                if (inboxList != null) {
                    val list = ArrayList<String>()
                    for (i in 0 until inboxList.length()) {
                        list.add(inboxList.getString(i))
                    }
                    n.inboxList = list
                }
            } catch (e: Exception) {
            }

            return n
        }

        @Throws(LocalNotificationsException::class)
        fun getLocalNotificationPendingList(notifications: JSONArray?): List<Int> {
            if (notifications == null || notifications.length() == 0) {
                throw LocalNotificationsError.INVALID_NOTIFICATIONS_ARRAY.toException()
            }
            val list = ArrayList<Int>(notifications.length())
            for (i in 0 until notifications.length()) {
                val o = notifications.optJSONObject(i)
                if (o != null) list.add(o.optInt("id"))
            }
            return list
        }

        fun buildLocalNotificationPendingList(notifications: List<LocalNotification>): JSONObject {
            val result = JSONObject()
            val jsArray = JSONArray()
            val sdf = SimpleDateFormat(LocalNotificationSchedule.JS_DATE_FORMAT)
            sdf.timeZone = TimeZone.getTimeZone("UTC")

            for (notification in notifications) {
                val jsNotification = JSONObject()
                jsNotification.put("id", notification.id)
                jsNotification.put("title", notification.title)
                jsNotification.put("body", notification.body)
                val schedule = notification.schedule
                if (schedule != null) {
                    val jsSchedule = JSONObject()
                    schedule.at?.let { jsSchedule.put("at", sdf.format(it)) }
                    jsSchedule.put("every", schedule.every)
                    jsSchedule.put("count", schedule.count)
                    jsSchedule.put("on", schedule.onObj)
                    jsSchedule.put("repeats", schedule.isRepeating())
                    jsNotification.put("schedule", jsSchedule)
                }
                jsNotification.put("extra", notification.extra)
                notification.sound?.let { jsNotification.put("sound", it) }
                notification.badge?.let { jsNotification.put("badge", it) }
                notification.foreground?.let { jsNotification.put("foreground", it) }
                jsArray.put(jsNotification)
            }
            result.put("notifications", jsArray)
            return result
        }
    }
}
