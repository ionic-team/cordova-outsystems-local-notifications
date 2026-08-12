package com.outsystems.plugins.localnotifications

import org.json.JSONObject

/**
 * Null-tolerant JSON getters replicating the semantics the Capacitor engine
 * relied on (Capacitor's JSObject). Replaces the Java `OSJSONObject` shim — in
 * Kotlin `org.json` setters are not checked, so plain `JSONObject` + these
 * extensions are enough and the covariant `put()` overrides are unnecessary.
 */

fun JSONObject.stringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name) else null

fun JSONObject.stringOr(name: String, defaultValue: String?): String? =
    if (has(name) && !isNull(name)) optString(name) else defaultValue

fun JSONObject.integerOrNull(name: String): Int? =
    if (!has(name) || isNull(name)) null else optInt(name)

fun JSONObject.integerOr(name: String, defaultValue: Int): Int =
    if (!has(name) || isNull(name)) defaultValue else optInt(name, defaultValue)

fun JSONObject.boolOrNull(name: String): Boolean? =
    if (!has(name) || isNull(name)) null else optBoolean(name)

fun JSONObject.booleanOr(name: String, defaultValue: Boolean): Boolean =
    optBoolean(name, defaultValue)

fun JSONObject.jsObject(name: String): JSONObject? = optJSONObject(name)
