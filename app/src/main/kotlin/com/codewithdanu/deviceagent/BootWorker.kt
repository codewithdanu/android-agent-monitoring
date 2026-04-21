package com.codewithdanu.deviceagent

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Ensures the AgentService is running.
 * Scheduled by BootReceiver or MainActivity as a failsafe.
 */
class BootWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = AgentConfig.getPrefs(applicationContext)
        val deviceId = prefs.getString(AgentConfig.KEY_DEVICE_ID, "")
        val serverUrl = prefs.getString(AgentConfig.KEY_SERVER_URL, "")

        if (!deviceId.isNullOrEmpty() && !serverUrl.isNullOrEmpty()) {
            Log.i("BootWorker", "Config found — ensuring AgentService is active")
            val intent = Intent(applicationContext, AgentService::class.java)
            applicationContext.startForegroundService(intent)
        } else {
            Log.w("BootWorker", "Agent not configured — skipping service start")
        }

        return Result.success()
    }
}
