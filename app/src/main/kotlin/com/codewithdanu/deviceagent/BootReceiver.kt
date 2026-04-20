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
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("AgentPrefs", Context.MODE_PRIVATE)
            val deviceId = prefs.getString("device_id", "")
            val serverUrl = prefs.getString("server_url", "")

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
