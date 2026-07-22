package com.outsystems.plugins.localnotifications

import android.text.format.DateUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import org.json.JSONObject

/**
 * Parsed representation of the Capacitor `schedule` option. Ported from the
 * Capacitor plugin.
 */
class LocalNotificationSchedule {

    var at: Date? = null
    var repeats: Boolean? = null
    var every: String? = null
    var count: Int = 1
    var on: DateMatch? = null

    private var whileIdle: Boolean = false
    private var scheduleObj: JSONObject? = null

    constructor()

    constructor(schedule: JSONObject) {
        scheduleObj = schedule
        every = schedule.stringOrNull("every")
        count = schedule.integerOr("count", 1)
        repeats = schedule.boolOrNull("repeats")

        val dateString = schedule.stringOrNull("at")
        if (dateString != null) {
            val sdf = SimpleDateFormat(JS_DATE_FORMAT)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            at = sdf.parse(dateString)
        }

        val onJson = schedule.jsObject("on")
        if (onJson != null) {
            val match = DateMatch()
            match.year = onJson.integerOrNull("year")
            match.month = onJson.integerOrNull("month")
            match.day = onJson.integerOrNull("day")
            match.weekday = onJson.integerOrNull("weekday")
            match.hour = onJson.integerOrNull("hour")
            match.minute = onJson.integerOrNull("minute")
            match.second = onJson.integerOrNull("second")
            on = match
        }

        whileIdle = schedule.booleanOr("allowWhileIdle", false)
    }

    val onObj: JSONObject?
        get() = scheduleObj?.jsObject("on")

    fun allowWhileIdle(): Boolean = whileIdle

    fun isRepeating(): Boolean = repeats == true

    fun isRemovable(): Boolean {
        if (every == null && on == null) {
            return if (at != null) !isRepeating() else true
        }
        return false
    }

    val everyInterval: Long?
        get() {
            val e = every ?: return null
            return when (e) {
                "year" -> count * DateUtils.WEEK_IN_MILLIS * 52
                "month" -> count * 30 * DateUtils.DAY_IN_MILLIS
                "two-weeks" -> count * 2 * DateUtils.WEEK_IN_MILLIS
                "week" -> count * DateUtils.WEEK_IN_MILLIS
                "day" -> count * DateUtils.DAY_IN_MILLIS
                "hour" -> count * DateUtils.HOUR_IN_MILLIS
                "minute" -> count * DateUtils.MINUTE_IN_MILLIS
                "second" -> count * DateUtils.SECOND_IN_MILLIS
                else -> null
            }
        }

    companion object {
        const val JS_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    }
}
