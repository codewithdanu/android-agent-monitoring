package com.codewithdanu.deviceagent

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * Ensures the AgentService is running.
 * Scheduled by BootReceiver or MainActivity as a failsafe.
 */
class BootWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo()
    }

    override suspend fun doWork(): Result {
        Log.d("BootWorker", "Worker triggered: ${System.currentTimeMillis()}")
        val prefs = AgentConfig.getPrefs(applicationContext)
        val deviceId = prefs.getString(AgentConfig.KEY_DEVICE_ID, "")
        val serverUrl = prefs.getString(AgentConfig.KEY_SERVER_URL, "")

        if (!deviceId.isNullOrEmpty() && !serverUrl.isNullOrEmpty()) {
            Log.i("BootWorker", "Config found — ensuring AgentService is active")
            try {
                val intent = Intent(applicationContext, AgentService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("BootWorker", "Failed to start service: ${e.message}")
            }
        } else {
            Log.w("BootWorker", "Agent not configured — skipping service start")
        }

        return Result.success()
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val channelId = "agent_boot_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Agent Startup"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(channelId, name, importance)
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Initializing Agent")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

        return ForegroundInfo(99, notification)
    }
}
