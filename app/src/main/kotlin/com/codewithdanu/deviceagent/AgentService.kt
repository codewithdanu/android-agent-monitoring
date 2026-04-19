package com.codewithdanu.deviceagent

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import androidx.lifecycle.LifecycleService
import org.json.JSONObject

/**
 * Persistent foreground service — keeps the agent alive even when app is backgrounded.
 * Runs metrics loop, location loop, and socket connection.
 */
class AgentService : LifecycleService() {
    private val TAG = "AgentService"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var locationHelper: LocationHelper
    private lateinit var deviceId: String
    private lateinit var deviceToken: String
    private lateinit var serverUrl: String

    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences(AgentConfig.PREFS_NAME, Context.MODE_PRIVATE)
        deviceId    = prefs.getString(AgentConfig.KEY_DEVICE_ID, "") ?: ""
        deviceToken = prefs.getString(AgentConfig.KEY_DEVICE_TOKEN, "") ?: ""
        serverUrl   = prefs.getString(AgentConfig.KEY_SERVER_URL, AgentConfig.SERVER_URL) ?: AgentConfig.SERVER_URL

        if (deviceId.isEmpty() || deviceToken.isEmpty()) {
            Log.e(TAG, "Device not configured — stopping service")
            stopSelf()
            return
        }

        locationHelper = LocationHelper(this)

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or 
                       ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or 
                       ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        connectAndMonitor()
        Log.i(TAG, "Service started for device: $deviceId connecting to $serverUrl")
    }

    private fun connectAndMonitor() {
        // Connect socket
        SocketManager.connect(this, serverUrl, deviceId, deviceToken) { cmd ->
            scope.launch { handleCommand(cmd) }
        }

        // Metrics loop
        scope.launch {
            while (isActive) {
                try {
                    sendMetrics()
                } catch (e: Exception) {
                    Log.e(TAG, "Metrics error: ${e.message}")
                }
                delay(AgentConfig.METRICS_INTERVAL_MS)
            }
        }

        // Location loop (adaptive interval based on battery)
        scope.launch {
            while (isActive) {
                try {
                    sendLocation()
                } catch (e: Exception) {
                    Log.e(TAG, "Location error: ${e.message}")
                }
                val battery = MetricsHelper.getBatteryPercent(this@AgentService) ?: 100
                val interval = if (battery < AgentConfig.LOW_BATTERY_THRESHOLD)
                    AgentConfig.LOCATION_INTERVAL_LOW_BATTERY
                else
                    AgentConfig.LOCATION_INTERVAL_MS

                Log.d(TAG, "Next location in ${interval / 60000}min (battery: $battery%)")
                delay(interval)
            }
        }
    }

    fun triggerMetricsUpdate() {
        scope.launch { sendMetrics() }
    }

    fun triggerLocationUpdate() {
        scope.launch { sendLocation() }
    }

    private suspend fun sendMetrics() {
        val metrics = MetricsHelper.collect(this, deviceId)
        SocketManager.emit("agent:metrics", metrics)
        Log.d(TAG, "Metrics sent: CPU=${metrics.optDouble("cpu_percent")}%")
    }

    private suspend fun sendLocation() {
        val loc = locationHelper.getLastLocation() ?: return
        val data = JSONObject().apply {
            put("deviceId",       deviceId)
            put("latitude",       loc.latitude)
            put("longitude",      loc.longitude)
            put("accuracy_meters", loc.accuracy)
        }
        SocketManager.emit("agent:location", data)
        Log.d(TAG, "Location sent: ${loc.latitude}, ${loc.longitude}")
    }

    private suspend fun handleCommand(cmd: JSONObject) {
        val commandId   = cmd.getString("commandId")
        val commandType = cmd.getString("command_type")
        val params      = cmd.optJSONObject("command_params")

        Log.i(TAG, "Executing command: $commandType")
        val result = CommandHandler.execute(this, commandType, params)

        val response = JSONObject().apply {
            put("commandId", commandId)
            put("status", if (result.has("error")) "FAILED" else "EXECUTED")
            put("result", result)
        }
        SocketManager.emit("agent:command_result", response)
    }

    // ─── NOTIFICATION ────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val channelId = "agent_service"
        val manager = getSystemService(NotificationManager::class.java)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Device Agent",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Device monitoring is active"
            }
            manager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Device Agent")
            .setContentText("Monitoring active · $serverUrl")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY  // Restart if killed
    }

    override fun onDestroy() {
        scope.cancel()
        SocketManager.disconnect()
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}
