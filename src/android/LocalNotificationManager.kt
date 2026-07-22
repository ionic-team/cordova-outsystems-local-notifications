package com.outsystems.plugins.localnotifications

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import java.text.SimpleDateFormat
import java.util.Date
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Notification scheduling / triggering engine, ported from the Capacitor plugin.
 * No Cordova/Capacitor types: JSON is plain `org.json`, config is a [Map], and
 * failures throw [LocalNotificationsException]. Action-button intents
 * (Capacitor-only) are omitted.
 */
class LocalNotificationManager(
    private val storage: NotificationStorage,
    private val activity: Activity?,
    private val context: Context,
    private val config: Map<String, String>
) {

    private fun configString(key: String): String? = config[key]

    /**
     * Handle a notification tap: build the action-performed payload.
     */
    fun handleNotificationActionPerformed(data: Intent, notificationStorage: NotificationStorage): JSONObject? {
        Log.d(LOG_TAG, "LocalNotification received: " + data.dataString)
        val notificationId = data.getIntExtra(NOTIFICATION_INTENT_KEY, Int.MIN_VALUE)
        if (notificationId == Int.MIN_VALUE) {
            Log.d(LOG_TAG, "Activity started without notification attached")
            return null
        }
        val isRemovable = data.getBooleanExtra(NOTIFICATION_IS_REMOVABLE_KEY, true)
        if (isRemovable) {
            notificationStorage.deleteNotification(notificationId.toString())
        }
        val dataJson = JSONObject()

        val results = RemoteInput.getResultsFromIntent(data)
        if (results != null) {
            val input = results.getCharSequence(REMOTE_INPUT_KEY)
            dataJson.put("inputValue", input?.toString())
        }
        val menuAction = data.getStringExtra(ACTION_INTENT_KEY)

        dismissVisibleNotification(notificationId)

        dataJson.put("actionId", menuAction)
        var request: JSONObject? = null
        try {
            val notificationJsonString = data.getStringExtra(NOTIFICATION_OBJ_INTENT_KEY)
            if (notificationJsonString != null) {
                request = JSONObject(notificationJsonString)
            }
        } catch (e: JSONException) {
        }
        dataJson.put("notification", request)
        return dataJson
    }

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name: CharSequence = "Default"
            val description = "Default"
            val importance = android.app.NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(DEFAULT_NOTIFICATION_CHANNEL_ID, name, importance)
            channel.description = description
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()
            val soundUri = getDefaultSoundUrl(context)
            if (soundUri != null) {
                channel.setSound(soundUri, audioAttributes)
            }
            val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    @Throws(LocalNotificationsException::class)
    fun schedule(localNotifications: List<LocalNotification>): JSONArray {
        val ids = JSONArray()
        val notificationManager = NotificationManagerCompat.from(context)

        if (!notificationManager.areNotificationsEnabled()) {
            throw LocalNotificationsError.NOTIFICATIONS_DISABLED.toException()
        }
        for (localNotification in localNotifications) {
            val id = localNotification.id ?: throw LocalNotificationsError.MISSING_IDENTIFIER.toException()
            // Reject a past scheduled time (parity with iOS and the Capacitor plugin; no silent drop).
            val at = localNotification.schedule?.at
            if (at != null && at.time < Date().time) {
                throw LocalNotificationsError.SCHEDULE_IN_PAST.toException()
            }
            dismissVisibleNotification(id)
            cancelTimerForNotification(id)
            buildNotification(notificationManager, localNotification)
            ids.put(id)
        }
        return ids
    }

    @Throws(LocalNotificationsException::class)
    private fun buildNotification(notificationManager: NotificationManagerCompat, localNotification: LocalNotification) {
        val id = localNotification.id ?: throw LocalNotificationsError.MISSING_IDENTIFIER.toException()
        val channelId = localNotification.channelId ?: DEFAULT_NOTIFICATION_CHANNEL_ID

        val foreground = localNotification.foreground == true
        val mBuilder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(localNotification.title)
            .setContentText(localNotification.body)
            .setAutoCancel(localNotification.autoCancel)
            .setOngoing(localNotification.ongoing)
            .setPriority(if (foreground) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setGroupSummary(localNotification.groupSummary)

        localNotification.badge?.let { mBuilder.setNumber(it) }

        if (localNotification.largeBody != null) {
            mBuilder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(localNotification.largeBody)
                    .setSummaryText(localNotification.summaryText)
            )
        }

        localNotification.inboxList?.let { lines ->
            val inboxStyle = NotificationCompat.InboxStyle()
            for (line in lines) inboxStyle.addLine(line)
            inboxStyle.setBigContentTitle(localNotification.title)
            inboxStyle.setSummaryText(localNotification.summaryText)
            mBuilder.setStyle(inboxStyle)
        }

        val sound = localNotification.resolveSound(context, getDefaultSound(context))
        if (sound != null) {
            val soundUri = Uri.parse(sound)
            context.grantUriPermission("com.android.systemui", soundUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            mBuilder.setSound(soundUri)
            mBuilder.setDefaults(Notification.DEFAULT_VIBRATE or Notification.DEFAULT_LIGHTS)
        } else {
            mBuilder.setDefaults(Notification.DEFAULT_ALL)
        }

        val group = localNotification.group
        if (group != null) {
            mBuilder.setGroup(group)
            if (localNotification.groupSummary) {
                mBuilder.setSubText(localNotification.summaryText)
            }
        }

        mBuilder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        mBuilder.setOnlyAlertOnce(true)

        mBuilder.setSmallIcon(localNotification.resolveSmallIcon(context, getDefaultSmallIcon(context)))
        mBuilder.setLargeIcon(localNotification.resolveLargeIcon(context))

        val iconColor = localNotification.resolveIconColor(configString("iconColor"))
        if (iconColor != null) {
            try {
                mBuilder.color = Color.parseColor(iconColor)
            } catch (ex: IllegalArgumentException) {
                throw LocalNotificationsError.INVALID_COLOR.toException()
            }
        }

        createActionIntents(localNotification, mBuilder)
        val buildNotification = mBuilder.build()
        if (localNotification.isScheduled()) {
            triggerScheduledNotification(buildNotification, localNotification)
        } else {
            try {
                val notificationJson = JSONObject(localNotification.source ?: "{}")
                OSLocalNotificationsPlugin.fireReceived(notificationJson)
            } catch (e: JSONException) {
            }
            notificationManager.notify(id, buildNotification)
        }
    }

    // Open + dismiss intents only. Action-button intents are Capacitor-only and omitted.
    private fun createActionIntents(localNotification: LocalNotification, mBuilder: NotificationCompat.Builder) {
        val id = localNotification.id ?: return
        val intent = buildIntent(localNotification, DEFAULT_PRESS_ACTION)
        var flags = PendingIntent.FLAG_CANCEL_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        val pendingIntent = PendingIntent.getActivity(context, id, intent, flags)
        mBuilder.setContentIntent(pendingIntent)

        val dismissIntent = Intent(context, NotificationDismissReceiver::class.java)
        dismissIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        dismissIntent.putExtra(NOTIFICATION_INTENT_KEY, id)
        dismissIntent.putExtra(ACTION_INTENT_KEY, "dismiss")
        val schedule = localNotification.schedule
        dismissIntent.putExtra(NOTIFICATION_IS_REMOVABLE_KEY, schedule == null || schedule.isRemovable())
        var deleteFlags = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            deleteFlags = PendingIntent.FLAG_MUTABLE
        }
        val deleteIntent = PendingIntent.getBroadcast(context, id, dismissIntent, deleteFlags)
        mBuilder.setDeleteIntent(deleteIntent)
    }

    private fun buildIntent(localNotification: LocalNotification, action: String): Intent {
        val intent = if (activity != null) {
            Intent(context, activity.javaClass)
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        }
        intent.action = Intent.ACTION_MAIN
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        intent.putExtra(NOTIFICATION_INTENT_KEY, localNotification.id)
        intent.putExtra(ACTION_INTENT_KEY, action)
        intent.putExtra(NOTIFICATION_OBJ_INTENT_KEY, localNotification.source)
        val schedule = localNotification.schedule
        intent.putExtra(NOTIFICATION_IS_REMOVABLE_KEY, schedule == null || schedule.isRemovable())
        return intent
    }

    private fun triggerScheduledNotification(notification: Notification, request: LocalNotification) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val schedule = request.schedule ?: return
        val requestId = request.id ?: return
        val notificationIntent = Intent(context, TimedNotificationPublisher::class.java)
        notificationIntent.putExtra(NOTIFICATION_INTENT_KEY, requestId)
        notificationIntent.putExtra(TimedNotificationPublisher.NOTIFICATION_KEY, notification)
        var flags = PendingIntent.FLAG_CANCEL_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        var pendingIntent = PendingIntent.getBroadcast(context, requestId, notificationIntent, flags)

        val at = schedule.at
        if (at != null) {
            if (at.time < Date().time) {
                Log.e(LOG_TAG, "Scheduled time must be *after* current time")
                return
            }
            if (schedule.isRepeating()) {
                val interval = at.time - Date().time
                alarmManager.setRepeating(AlarmManager.RTC, at.time, interval, pendingIntent)
            } else {
                setExactIfPossible(alarmManager, schedule, at.time, pendingIntent)
            }
            return
        }

        val every = schedule.every
        if (every != null) {
            val everyInterval = schedule.everyInterval
            if (everyInterval != null) {
                val startTime = Date().time + everyInterval
                alarmManager.setRepeating(AlarmManager.RTC, startTime, everyInterval, pendingIntent)
            }
            return
        }

        val on = schedule.on
        if (on != null) {
            val trigger = on.nextTrigger(Date())
            notificationIntent.putExtra(TimedNotificationPublisher.CRON_KEY, on.toMatchString())
            pendingIntent = PendingIntent.getBroadcast(context, requestId, notificationIntent, flags)
            setExactIfPossible(alarmManager, schedule, trigger, pendingIntent)
            val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss")
            Log.d(LOG_TAG, "notification " + requestId + " will next fire at " + sdf.format(Date(trigger)))
        }
    }

    private fun setExactIfPossible(
        alarmManager: AlarmManager,
        schedule: LocalNotificationSchedule,
        trigger: Long,
        pendingIntent: PendingIntent
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(LOG_TAG, "Exact alarms not allowed in user settings. Notification scheduled with non-exact alarm.")
            if (schedule.allowWhileIdle()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC, trigger, pendingIntent)
            }
        } else {
            if (schedule.allowWhileIdle()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC, trigger, pendingIntent)
            }
        }
    }

    fun cancel(notificationsToCancel: List<Int>?) {
        if (notificationsToCancel != null) {
            for (id in notificationsToCancel) {
                dismissVisibleNotification(id)
                cancelTimerForNotification(id)
                storage.deleteNotification(id.toString())
            }
        }
    }

    /**
     * Cancel all pending (scheduled) notifications.
     */
    fun cancelAll() {
        for (idStr in storage.getSavedNotificationIds()) {
            val id = idStr.toIntOrNull() ?: continue
            dismissVisibleNotification(id)
            cancelTimerForNotification(id)
            storage.deleteNotification(idStr)
        }
    }

    private fun cancelTimerForNotification(notificationId: Int) {
        val intent = Intent(context, TimedNotificationPublisher::class.java)
        var flags = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = PendingIntent.FLAG_MUTABLE
        }
        val pi = PendingIntent.getBroadcast(context, notificationId, intent, flags)
        if (pi != null) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pi)
        }
    }

    private fun dismissVisibleNotification(notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    fun areNotificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun getDefaultSoundUrl(context: Context): Uri? {
        val soundId = getDefaultSound(context)
        return if (soundId != AssetUtil.RESOURCE_ID_ZERO_VALUE) {
            Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.packageName + "/" + soundId)
        } else {
            null
        }
    }

    private fun getDefaultSound(context: Context): Int {
        if (defaultSoundID != AssetUtil.RESOURCE_ID_ZERO_VALUE) return defaultSoundID
        var resId = AssetUtil.RESOURCE_ID_ZERO_VALUE
        val soundConfigResourceName = AssetUtil.getResourceBaseName(configString("sound"))
        if (soundConfigResourceName != null) {
            resId = AssetUtil.getResourceID(context, soundConfigResourceName, "raw")
        }
        defaultSoundID = resId
        return resId
    }

    private fun getDefaultSmallIcon(context: Context): Int {
        if (defaultSmallIconID != AssetUtil.RESOURCE_ID_ZERO_VALUE) return defaultSmallIconID
        var resId = AssetUtil.RESOURCE_ID_ZERO_VALUE
        val smallIconConfigResourceName = AssetUtil.getResourceBaseName(configString("smallIcon"))
        if (smallIconConfigResourceName != null) {
            resId = AssetUtil.getResourceID(context, smallIconConfigResourceName, "drawable")
        }
        if (resId == AssetUtil.RESOURCE_ID_ZERO_VALUE) {
            resId = android.R.drawable.ic_dialog_info
        }
        defaultSmallIconID = resId
        return resId
    }

    companion object {
        const val NOTIFICATION_INTENT_KEY = "LocalNotificationId"
        const val NOTIFICATION_OBJ_INTENT_KEY = "LocalNotficationObject"
        const val ACTION_INTENT_KEY = "LocalNotificationUserAction"
        const val NOTIFICATION_IS_REMOVABLE_KEY = "LocalNotificationRepeating"
        const val REMOTE_INPUT_KEY = "LocalNotificationRemoteInput"
        const val DEFAULT_NOTIFICATION_CHANNEL_ID = "default"

        private const val DEFAULT_PRESS_ACTION = "tap"
        private const val LOG_TAG = "OSLocalNotifications"

        private var defaultSoundID = AssetUtil.RESOURCE_ID_ZERO_VALUE
        private var defaultSmallIconID = AssetUtil.RESOURCE_ID_ZERO_VALUE
    }
}
