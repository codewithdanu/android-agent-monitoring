package com.codewithdanu.deviceagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the AgentService automatically on device boot.
 * Requires RECEIVE_BOOT_COMPLETED permission.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val actions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )

        if (intent.action in actions) {
            val prefs = AgentConfig.getPrefs(context)
            val deviceId = prefs.getString(AgentConfig.KEY_DEVICE_ID, "")
            val serverUrl = prefs.getString(AgentConfig.KEY_SERVER_URL, "")

            if (!deviceId.isNullOrEmpty() && !serverUrl.isNullOrEmpty()) {
                Log.i("BootReceiver", "Boot completed & configured — starting AgentService")
                val serviceIntent = Intent(context, AgentService::class.java)
                context.startForegroundService(serviceIntent)
            } else {
                Log.w("BootReceiver", "Boot completed but agent not configured — skipping start")
            }
        }
    }
}
