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
        LegacyNotificationMigrator.run(context, notificationStorage, manager)

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
        // The exact-alarm prompt only applies to schedule (not update), and only
        // when any notification in this batch wants an exact alarm at all
        // (isExactNotification:true, the default) — independent of whether it's
        // mandatory. Mandatory only decides what happens afterward if the user
        // still declines: performScheduleNow rejects the call for a mandatory
        // notification, or falls back to inexact (with a warning) otherwise.
        val honorExact = if (onlyExisting) {
            false
        } else {
            try {
                val options = args.optJSONObject(0)
                val notifications = LocalNotification.buildNotificationList(options?.optJSONArray("notifications"))
                notifications.any { it.isExactNotification }
            } catch (ex: LocalNotificationsException) {
                callbackContext.error(ex.toJson())
                return
            }
        }
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
        // (exact if granted; otherwise rejected if a mandatory notification is
        // still denied, or falls back to inexact with a warning otherwise).
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

            // All-or-nothing: if exact-alarm permission is currently missing and any
            // notification in this batch marks it mandatory, reject the whole call
            // instead of silently scheduling some of it as inexact. Schedule only —
            // matches the legacy plugin, where update() never checks this and just
            // lets the low-level engine try exact and silently fall back.
            if (!onlyExisting && !canScheduleExactAlarms() &&
                localNotifications.any { it.isExactNotification && it.isExactMandatory }
            ) {
                throw LocalNotificationsError.EXACT_ALARM_PERMISSION_REQUIRED.toException()
            }

            val ids = manager.schedule(localNotifications)
            notificationStorage.appendNotifications(localNotifications)

            val result = JSONObject()
            val jsArray = JSONArray()
            for (i in 0 until ids.length()) {
                jsArray.put(JSONObject().put("id", ids.getInt(i)))
            }
            result.put("notifications", jsArray)
            // Schedule only, matching the legacy plugin (update() never carries this
            // signal). Any exact-wanting notification that got silently downgraded to
            // inexact (permission denied, not mandatory — mandatory already rejected
            // the whole call above) is surfaced here as a non-fatal warning.
            if (!onlyExisting && !canScheduleExactAlarms() && localNotifications.any { it.isExactNotification }) {
                result.put("warning", LocalNotificationsError.SCHEDULED_INEXACT.toJson())
            }
            callbackContext.success(result)
        } catch (ex: LocalNotificationsException) {
            callbackContext.error(ex.toJson())
        }
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
        // Forget only notifications with no reason left to be kept: an
        // already-triggered one-shot, or a perpetual schedule that's been marked
        // cancelled. A perpetual notification still genuinely scheduled
        // survives, even though its currently-visible instance was just
        // dismissed from the shade above.
        for (idStr in notificationStorage.getSavedNotificationIds()) {
            val existing = notificationStorage.getSavedNotification(idStr)
            if (LocalNotificationManager.isSafeToForget(existing)) {
                notificationStorage.deleteNotification(idStr)
            }
        }
        callbackContext.success()
    }

    /**
     * Forget a delivered notification's storage record — but only once it's
     * actually safe to (see [LocalNotificationManager.isSafeToForget]). Clearing
     * a not-yet-triggered notification must never silently cancel it, and
     * clearing a still-active perpetual schedule must never orphan its series.
     */
    private fun removeFromStorageIfRemovable(id: Int) {
        val existing = notificationStorage.getSavedNotification(id.toString())
        if (LocalNotificationManager.isSafeToForget(existing)) {
            notificationStorage.deleteNotification(id.toString())
        }
    }

    // --- Queries ---

    /**
     * Get the notifications (scheduled and/or delivered) matching the ids. Same
     * "everything valid" semantics as getAll() with no state filter, just also
     * constrained to the requested ids.
     */
    private fun getByIds(args: JSONArray, callbackContext: CallbackContext) {
        val options = args.optJSONObject(0)
        val ids = parseIds(options?.optJSONArray("ids"))
        if (ids == null) {
            callbackContext.error(LocalNotificationsError.MISSING_IDS.toJson())
            return
        }

        val notifications = JSONArray()
        val activeIds = manager.currentlyVisibleIds()
        val matched = notificationStorage.getSavedNotifications().filter { n ->
            val nid = n.id
            nid != null && ids.contains(nid) && matchesState(n, null, activeIds)
        }
        val matchedResult = LocalNotification.buildLocalNotificationPendingList(matched)
        appendNotifications(notifications, matchedResult.optJSONArray("notifications"))

        resolveNotifications(callbackContext, notifications)
    }

    private fun getAll(args: JSONArray, callbackContext: CallbackContext) {
        val options = args.optJSONObject(0)
        val state = if (options != null && !options.isNull("state")) options.optString("state") else null
        val notifications = JSONArray()

        val activeIds = manager.currentlyVisibleIds()
        val filtered = notificationStorage.getSavedNotifications().filter { matchesState(it, state, activeIds) }
        val result = LocalNotification.buildLocalNotificationPendingList(filtered)
        appendNotifications(notifications, result.optJSONArray("notifications"))

        resolveNotifications(callbackContext, notifications)
    }

    /**
     * Whether [n] belongs in the requested state bucket. No state (null) means
     * "everything valid" — the union of SCHEDULED and TRIGGERED.
     */
    private fun matchesState(n: LocalNotification, state: String?, activeIds: Set<Int>): Boolean {
        return when (state) {
            "SCHEDULED" -> manager.isCurrentlyScheduled(n)
            "TRIGGERED" -> manager.isCurrentlyTriggered(n, activeIds)
            else -> manager.isCurrentlyScheduled(n) || manager.isCurrentlyTriggered(n, activeIds)
        }
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
