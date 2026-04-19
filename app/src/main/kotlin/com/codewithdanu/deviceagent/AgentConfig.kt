package com.codewithdanu.deviceagent

/**
 * Global agent configuration constants.
 * Edit SERVER_URL before building.
 */
object AgentConfig {
    // ─── DEFAULT VALUES ───────────────────────────────────────────────────
    var SERVER_URL = "http://IP_ADDRESS:3000" // Can be updated via QR
    // ──────────────────────────────────────────────────────────────────────

    const val PREFS_NAME        = "AgentPrefs"
    const val KEY_DEVICE_ID     = "device_id"
    const val KEY_DEVICE_TOKEN  = "device_token"
    const val KEY_SERVER_URL    = "server_url"

    /** Metrics push interval: 1 minute */
    const val METRICS_INTERVAL_MS = 60_000L

    /** Location update interval: 5 minutes (normal battery) */
    const val LOCATION_INTERVAL_MS = 300_000L

    /** Location update interval: 30 minutes (battery < 15%) */
    const val LOCATION_INTERVAL_LOW_BATTERY = 1_800_000L

    /** Battery threshold for switching to low-power location mode */
    const val LOW_BATTERY_THRESHOLD = 15
}
