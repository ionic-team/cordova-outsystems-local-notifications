package com.outsystems.plugins.localnotifications

import android.content.Context

/**
 * Minimal resource-resolution helper (subset of Capacitor's AssetUtil).
 */
object AssetUtil {

    const val RESOURCE_ID_ZERO_VALUE = 0

    /**
     * Strip any `res://` / `file://` scheme, directory and extension, returning
     * the bare resource base name (or `null`).
     */
    fun getResourceBaseName(resPath: String?): String? {
        if (resPath.isNullOrEmpty()) return null

        var name = resPath
        when {
            name.startsWith("res://") -> name = name.substring("res://".length)
            name.startsWith("file://") -> name = name.substring("file://".length)
        }

        val slash = name.lastIndexOf('/')
        if (slash >= 0) name = name.substring(slash + 1)

        val dot = name.lastIndexOf('.')
        if (dot > 0) name = name.substring(0, dot)

        return name.ifEmpty { null }
    }

    fun getResourceID(context: Context, name: String?, type: String): Int {
        if (name == null) return RESOURCE_ID_ZERO_VALUE
        return context.resources.getIdentifier(name, type, context.packageName)
    }
}
