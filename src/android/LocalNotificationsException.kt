package com.outsystems.plugins.localnotifications

import org.json.JSONObject

/**
 * Throwable wrapper around [LocalNotificationsError] (Kotlin enums are not
 * Throwable). The bridge maps it back to `callbackContext.error(exception.toJson())`.
 */
class LocalNotificationsException(val error: LocalNotificationsError) : Exception(error.message) {
    fun toJson(): JSONObject = error.toJson()
}
