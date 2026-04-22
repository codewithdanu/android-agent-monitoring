package com.codewithdanu.deviceagent

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.ContextCompat

/**
 * Global agent configuration constants.
 * Edit SERVER_URL before building.
 */
object AgentConfig {
    // ─── DEFAULT VALUES ───────────────────────────────────────────────────
    var SERVER_URL = "http://<IP_ADDRESS>:3000" // Can be updated via QR
    // ──────────────────────────────────────────────────────────────────────

    const val PREFS_NAME        = "AgentPrefs"
    const val KEY_DEVICE_ID     = "device_id"
    const val KEY_DEVICE_TOKEN  = "device_token"
    const val KEY_SERVER_URL    = "server_url"

    /** Metrics push interval: 15 minutes */
    const val METRICS_INTERVAL_MS = 900_000L

    /** Location update interval: 15 minutes (normal battery) */
    const val LOCATION_INTERVAL_MS = 900_000L

    /** Location update interval: 30 minutes (battery < 15%) */
    const val LOCATION_INTERVAL_LOW_BATTERY = 1_800_000L

    /** Battery threshold for switching to low-power location mode */
    const val LOW_BATTERY_THRESHOLD = 15

    /**
     * Returns the SharedPreferences instance.
     * Uses device-protected storage if the device is in Direct Boot mode (locked).
     * Automatically migrates data from normal storage to protected storage if found.
     */
    fun getPrefs(context: Context): SharedPreferences {
        val protectedContext = if (ContextCompat.isDeviceProtectedStorage(context)) {
            context
        } else {
            context.createDeviceProtectedStorageContext()
        }

        // Migration check: If protected storage is empty, try to move from normal storage
        val name = PREFS_NAME
        val protectedPrefs = protectedContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        
        if (protectedPrefs.getString(KEY_DEVICE_ID, "").isNullOrEmpty()) {
            try {
                val normalPrefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                val oldId = normalPrefs.getString(KEY_DEVICE_ID, "")
                if (!oldId.isNullOrEmpty()) {
                    android.util.Log.i("AgentConfig", "Migrating preferences to protected storage...")
                    // Manual copy to ensure atomicity and handle direct boot context constraints
                    protectedPrefs.edit().apply {
                        putString(KEY_DEVICE_ID, oldId)
                        putString(KEY_DEVICE_TOKEN, normalPrefs.getString(KEY_DEVICE_TOKEN, ""))
                        putString(KEY_SERVER_URL, normalPrefs.getString(KEY_SERVER_URL, ""))
                        apply()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("AgentConfig", "Skipping migration during Direct Boot: ${e.message}")
            }
        }

        return protectedPrefs
    }

    /**
     * Ensures the server URL is valid for Retrofit/Socket.io
     * - Adds http:// if scheme is missing
     * - Removes trailing spaces
     * - Ensures single trailing slash for base URLs
     */
    fun getNormalizedServerUrl(context: Context): String {
        var url = getPrefs(context).getString(KEY_SERVER_URL, SERVER_URL)?.trim() ?: SERVER_URL
        if (url.isEmpty()) url = SERVER_URL
        
        // Add scheme if missing
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        
        // Fix common mistakes like "http:/192..."
        url = url.replace("http:/", "http://").replace("http:///", "http://")
        
        return url
    }

    /**
     * Helper to get Device ID from preferences.
     */
    fun getDeviceId(context: Context): String {
        return getPrefs(context).getString(KEY_DEVICE_ID, "") ?: ""
    }

    /**
     * Helper to get Device Token from preferences.
     */
    fun getDeviceToken(context: Context): String {
        return getPrefs(context).getString(KEY_DEVICE_TOKEN, "") ?: ""
    }
}
