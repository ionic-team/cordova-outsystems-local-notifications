package com.outsystems.plugins.localnotifications

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.PermissionHelper
import org.apache.cordova.PluginResult
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Cordova bridge for the OutSystems Local Notifications plugin. Thin framework
 * layer only — all scheduling / triggering logic lives in the shared engine
 * ([LocalNotificationManager] and friends), a Kotlin port of the Capacitor
 * implementation. Exposes the SAME method names, option keys, return shapes and
 * events as `@capacitor/local-notifications` for the shared API.
 */
class OSLocalNotificationsPlugin : CordovaPlugin() {

    private lateinit var manager: LocalNotificationManager
    private lateinit var notificationStorage: NotificationStorage
    private lateinit var notificationManager: NotificationManager

    // Persistent (keepCallback) channel used to push events to JS.
    private var eventCallbackContext: CallbackContext? = null
    // A tap that arrived before the JS listener was registered (cold start).
    private var pendingActionPerformed: JSONObject? = null

    // Pending schedule/update awaiting the POST_NOTIFICATIONS permission result.
    private var pendingCall: CallbackContext? = null
    private var pendingArgs: JSONArray? = null
    private var pendingIsUpdate: Boolean = false

    // Pending schedule awaiting the exact-alarm settings screen (resumes in onActivityResult).
    private var pendingExactCall: CallbackContext? = null
    private var pendingExactArgs: JSONArray? = null

    override fun pluginInitialize() {
        super.pluginInitialize()
        instance = this
        val context = cordova.activity.applicationContext
        notificationStorage = NotificationStorage(context)
        manager = LocalNotificationManager(notificationStorage, cordova.activity, context, emptyMap())
        manager.createNotificationChannel()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Capture a cold-start notification tap, if any.
        captureActionPerformed(cordova.activity.intent)
    }

    override fun execute(action: String, args: JSONArray, callbackContext: CallbackContext): Boolean {
        when (action) {
            "startEventListener" -> startEventListener(callbackContext)
            "schedule" -> cordova.threadPool.execute { scheduleAction(args, callbackContext, false) }
            "update" -> cordova.threadPool.execute { scheduleAction(args, callbackContext, true) }
            "cancel" -> cordova.threadPool.execute { cancel(args, callbackContext) }
            "cancelAll" -> cordova.threadPool.execute { cancelAll(callbackContext) }
            "removeDeliveredNotifications" -> cordova.threadPool.execute { removeDeliveredNotifications(args, callbackContext) }
            "removeDeliveredNotificationsById" -> cordova.threadPool.execute { removeDeliveredNotificationsById(args, callbackContext) }
            "removeAllDeliveredNotifications" -> cordova.threadPool.execute { removeAllDeliveredNotifications(callbackContext) }
            "getByIds" -> cordova.threadPool.execute { getByIds(args, callbackContext) }
            "getAll" -> cordova.threadPool.execute { getAll(args, callbackContext) }
            else -> return false
        }
        return true
    }

    // --- Scheduling (implicit POST_NOTIFICATIONS + optional exact-alarm prompt) ---

    private fun scheduleAction(args: JSONArray, callbackContext: CallbackContext, isUpdate: Boolean) {
        if (shouldRequestNotificationPermission()) {
            pendingCall = callbackContext
            pendingArgs = args
            pendingIsUpdate = isUpdate
            cordova.activity.runOnUiThread {
                PermissionHelper.requestPermission(this, SCHEDULE_PERMISSION_CODE, POST_NOTIFICATIONS)
            }
        } else {
            doSchedule(args, callbackContext, isUpdate)
        }
    }

    @Deprecated("Overrides CordovaPlugin's deprecated permission callback, still invoked by cordova-android.")
    @Throws(JSONException::class)
    override fun onRequestPermissionResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionResult(requestCode, permissions, grantResults)
        if (requestCode == SCHEDULE_PERMISSION_CODE) {
            val args = pendingArgs
            val call = pendingCall
            val isUpdate = pendingIsUpdate
            pendingArgs = null
            pendingCall = null
            pendingIsUpdate = false
            // Proceed regardless of grant result; the engine rejects with
            // NOTIFICATIONS_DISABLED if notifications remain disabled.
            if (args != null && call != null) {
                cordova.threadPool.execute { doSchedule(args, call, isUpdate) }
            }
        }
    }

    private fun shouldRequestNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !PermissionHelper.hasPermission(this, POST_NOTIFICATIONS)

    private fun doSchedule(args: JSONArray, callbackContext: CallbackContext, onlyExisting: Boolean) {
        // The exact-alarm prompt only applies to schedule (not update) and only
        // when the caller opts in with exactAlarm:true.
        val honorExact = !onlyExisting && optExactAlarm(args)
        if (honorExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExactAlarms()) {
            pendingExactArgs = args
            pendingExactCall = callbackContext
            // Go to this app's exact-alarm toggle
            cordova.activity.runOnUiThread {
                cordova.startActivityForResult(
                    this,
                    Intent(ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + cordova.activity.packageName)),
                    EXACT_ALARM_REQUEST_CODE
                )
            }
            return
        }
        performScheduleNow(args, callbackContext, onlyExisting)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        if (requestCode != EXACT_ALARM_REQUEST_CODE) return
        // Returned from the "Alarms & reminders" settings screen: schedule now
        // (exact if granted, otherwise inexact with a warning).
        val args = pendingExactArgs
        val call = pendingExactCall
        pendingExactArgs = null
        pendingExactCall = null
        if (args != null && call != null) {
            cordova.threadPool.execute { performScheduleNow(args, call, false) }
        }
    }

    private fun performScheduleNow(args: JSONArray, callbackContext: CallbackContext, onlyExisting: Boolean) {
        try {
            val options = args.optJSONObject(0)
            val notificationsArray = options?.optJSONArray("notifications")
            val localNotifications = LocalNotification.buildNotificationList(notificationsArray).toMutableList()

            if (onlyExisting) {
                val savedIds = notificationStorage.getSavedNotificationIds()
                localNotifications.removeAll { n ->
                    val id = n.id
                    id == null || !savedIds.contains(id.toString())
                }
            }

            val ids = manager.schedule(localNotifications)
            notificationStorage.appendNotifications(localNotifications)

            val result = JSONObject()
            val jsArray = JSONArray()
            for (i in 0 until ids.length()) {
                jsArray.put(JSONObject().put("id", ids.getInt(i)))
            }
            result.put("notifications", jsArray)

            // exactAlarm was requested but still not permitted -> inexact fallback.
            val honorExact = !onlyExisting && optExactAlarm(args)
            if (honorExact && !canScheduleExactAlarms()) {
                result.put("warning", LocalNotificationsError.SCHEDULED_INEXACT.toJson())
            }
            callbackContext.success(result)
        } catch (ex: LocalNotificationsException) {
            callbackContext.error(ex.toJson())
        }
    }

    private fun optExactAlarm(args: JSONArray): Boolean {
        val options = args.optJSONObject(0)
        return options != null && options.optBoolean("exactAlarm", false)
    }

    private fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = cordova.activity.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        return alarmManager == null || alarmManager.canScheduleExactAlarms()
    }

    // --- Cancel ---

    private fun cancel(args: JSONArray, callbackContext: CallbackContext) {
        try {
            val options = args.optJSONObject(0)
            val ids = LocalNotification.getLocalNotificationPendingList(options?.optJSONArray("notifications"))
            manager.cancel(ids)
            callbackContext.success()
        } catch (ex: LocalNotificationsException) {
            callbackContext.error(ex.toJson())
        }
    }

    private fun cancelAll(callbackContext: CallbackContext) {
        manager.cancelAll()
        callbackContext.success()
    }

    // --- Delivered notifications ---

    private fun removeDeliveredNotifications(args: JSONArray, callbackContext: CallbackContext) {
        val options = args.optJSONObject(0)
        val notifications = options?.optJSONArray("notifications")
        if (notifications == null) {
            callbackContext.error(LocalNotificationsError.INVALID_REMOVE_LIST.toJson())
            return
        }
        for (i in 0 until notifications.length()) {
            val notif = notifications.optJSONObject(i)
            if (notif == null) {
                callbackContext.error(LocalNotificationsError.INVALID_REMOVE_LIST.toJson())
                return
            }
            val tag = if (notif.isNull("tag")) null else notif.optString("tag")
            val id = notif.optInt("id")
            if (tag == null) notificationManager.cancel(id) else notificationManager.cancel(tag, id)
            removeFromStorageIfRemovable(id)
        }
        callbackContext.success()
    }

    private fun removeDeliveredNotificationsById(args: JSONArray, callbackContext: CallbackContext) {
        val options = args.optJSONObject(0)
        val ids = parseIds(options?.optJSONArray("ids"))
        if (ids == null) {
            callbackContext.error(LocalNotificationsError.MISSING_IDS.toJson())
            return
        }
        for (id in ids) {
            notificationManager.cancel(id)
            removeFromStorageIfRemovable(id)
        }
        callbackContext.success()
    }

    private fun removeAllDeliveredNotifications(callbackContext: CallbackContext) {
        notificationManager.cancelAll()
        // Forget already-triggered, non-perpetual notifications outright. A
        // perpetual (every/on/repeats) schedule keeps its storage record only
        // while its alarm is still genuinely active — cancel()/cancelAll() can
        // leave a cancelled-but-still-visible perpetual record behind, and once
        // it's also no longer scheduled to fire again, dismissing it here must
        // not leave an orphan with no value under either SCHEDULED or TRIGGERED.
        for (idStr in notificationStorage.getSavedNotificationIds()) {
            val existing = notificationStorage.getSavedNotification(idStr)
            val id = idStr.toIntOrNull()
            val perpetual = existing?.schedule?.isPerpetual() == true
            if (existing?.isTriggered() == true || (perpetual && id != null && !manager.isAlarmActive(id))) {
                notificationStorage.deleteNotification(idStr)
            }
        }
        callbackContext.success()
    }

    /**
     * Forget a delivered notification's storage record, unless it's part of a
     * perpetual (every/on/repeats) schedule — matching the dismiss-receiver's
     * isRemovable() rule, so clearing one delivered instance never orphans a
     * still-active repeating alarm.
     */
    private fun removeFromStorageIfRemovable(id: Int) {
        val existing = notificationStorage.getSavedNotification(id.toString())
        val removable = existing?.schedule?.isRemovable() ?: true
        // A perpetual schedule is never "removable" by shape alone, but once its
        // alarm has actually been cancelled there's no series left to preserve
        // the record for — keep it only while genuinely still scheduled.
        val perpetualAndDead = existing?.schedule?.isPerpetual() == true && !manager.isAlarmActive(id)
        if (removable || perpetualAndDead) {
            notificationStorage.deleteNotification(id.toString())
        }
    }

    // --- Queries ---

    private fun getByIds(args: JSONArray, callbackContext: CallbackContext) {
        val options = args.optJSONObject(0)
        val ids = parseIds(options?.optJSONArray("ids"))
        if (ids == null) {
            callbackContext.error(LocalNotificationsError.MISSING_IDS.toJson())
            return
        }

        val notifications = JSONArray()
        val matched = ArrayList<LocalNotification>()
        for (n in notificationStorage.getSavedNotifications()) {
            val nid = n.id
            if (nid != null && ids.contains(nid)) matched.add(n)
        }
        val matchedResult = LocalNotification.buildLocalNotificationPendingList(matched)
        appendNotifications(notifications, matchedResult.optJSONArray("notifications"))

        resolveNotifications(callbackContext, notifications)
    }

    private fun getAll(args: JSONArray, callbackContext: CallbackContext) {
        val options = args.optJSONObject(0)
        val state = if (options != null && !options.isNull("state")) options.optString("state") else null
        val notifications = JSONArray()

        val all = notificationStorage.getSavedNotifications()
        // A perpetual (every/on/repeats) schedule is "scheduled" only while its
        // alarm is genuinely still registered — cancel/cancelAll can leave its
        // storage record behind (so a still-visible delivered instance keeps
        // showing under TRIGGERED) without it still being scheduled to fire again.
        // Once it has fired at least once and is still visible in the shade, it's
        // ALSO "triggered".
        val activeIds = notificationManager.activeNotifications.map { it.id }.toSet()
        val isScheduled = { n: LocalNotification ->
            val perpetual = n.schedule?.isPerpetual() == true
            val nid = n.id
            !n.isTriggered() && (!perpetual || (nid != null && manager.isAlarmActive(nid)))
        }
        val isTriggered = { n: LocalNotification ->
            val nid = n.id
            n.isTriggered() || (n.schedule?.isPerpetual() == true && nid != null && activeIds.contains(nid))
        }
        // No filter = everything valid, i.e. the union of SCHEDULED and
        // TRIGGERED — not raw storage. A record can outlive both (e.g. a
        // perpetual schedule that was cancelled while still visible, then
        // dismissed) and must not resurface here either.
        val filtered = when (state) {
            "SCHEDULED" -> all.filter(isScheduled)
            "TRIGGERED" -> all.filter(isTriggered)
            else -> all.filter { n -> isScheduled(n) || isTriggered(n) }
        }
        val result = LocalNotification.buildLocalNotificationPendingList(filtered)
        appendNotifications(notifications, result.optJSONArray("notifications"))

        resolveNotifications(callbackContext, notifications)
    }

    private fun resolveNotifications(callbackContext: CallbackContext, notifications: JSONArray) {
        val result = JSONObject()
        result.put("notifications", notifications)
        callbackContext.success(result)
    }

    private fun parseIds(idsArray: JSONArray?): List<Int>? {
        if (idsArray == null) return null
        val ids = ArrayList<Int>()
        for (i in 0 until idsArray.length()) ids.add(idsArray.optInt(i))
        return ids
    }

    private fun appendNotifications(target: JSONArray, source: JSONArray?) {
        if (source == null) return
        for (i in 0 until source.length()) {
            target.put(source.get(i))
        }
    }

    // --- Events ---

    private fun startEventListener(callbackContext: CallbackContext) {
        eventCallbackContext = callbackContext
        val keepAlive = PluginResult(PluginResult.Status.NO_RESULT)
        keepAlive.keepCallback = true
        callbackContext.sendPluginResult(keepAlive)

        // Flush a cold-start tap captured before the listener registered.
        pendingActionPerformed?.let {
            emitEvent(EVENT_ACTION_PERFORMED, it)
            pendingActionPerformed = null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureActionPerformed(intent)
        val pending = pendingActionPerformed
        if (pending != null && eventCallbackContext != null) {
            emitEvent(EVENT_ACTION_PERFORMED, pending)
            pendingActionPerformed = null
        }
    }

    private fun captureActionPerformed(intent: Intent?) {
        if (intent == null || Intent.ACTION_MAIN != intent.action) return
        val data = manager.handleNotificationActionPerformed(intent, notificationStorage)
        if (data != null) pendingActionPerformed = data
    }

    private fun emitEvent(eventName: String, data: JSONObject) {
        val cb = eventCallbackContext ?: return
        val payload = JSONObject()
        payload.put("eventName", eventName)
        payload.put("data", data)
        val result = PluginResult(PluginResult.Status.OK, payload)
        result.keepCallback = true
        cb.sendPluginResult(result)
    }

    companion object {
        private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        private const val SCHEDULE_PERMISSION_CODE = 43334
        private const val EXACT_ALARM_REQUEST_CODE = 43335

        private const val EVENT_RECEIVED = "localNotificationReceived"
        private const val EVENT_ACTION_PERFORMED = "localNotificationActionPerformed"

        private var instance: OSLocalNotificationsPlugin? = null

        /**
         * Called by the engine when a notification is delivered/shown.
         */
        fun fireReceived(notification: JSONObject?) {
            if (notification != null) {
                instance?.emitEvent(EVENT_RECEIVED, notification)
            }
        }
    }
}
