package com.codewithdanu.deviceagent

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
    private lateinit var powerManager: android.os.PowerManager
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    
    private lateinit var deviceId: String
    private lateinit var deviceToken: String
    private lateinit var serverUrl: String
    
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.i(TAG, "Internet available — ensuring socket is connected")
            if (!SocketManager.isConnected()) {
                SocketManager.reconnect()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AgentService.onCreate() — Startup initiated")

        val prefs = AgentConfig.getPrefs(this)
        deviceId    = prefs.getString(AgentConfig.KEY_DEVICE_ID, "") ?: ""
        deviceToken = prefs.getString(AgentConfig.KEY_DEVICE_TOKEN, "") ?: ""
        serverUrl   = AgentConfig.getNormalizedServerUrl(this)

        Log.d(TAG, "Config loaded: ID=$deviceId URL=$serverUrl")
        if (deviceId.isEmpty()) {
            Log.e(TAG, "Device not configured — stopping service")
            stopSelf()
            return
        }

        locationHelper = LocationHelper(this)
        
        powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Agent:WakeLock")

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or 
                       ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or 
                       ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                       ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        connectAndMonitor()
        registerNetworkCallback()
        Log.i(TAG, "Service started for device: $deviceId connecting to $serverUrl")
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, networkCallback)
    }

    private fun connectAndMonitor() {
        Log.d(TAG, "Initiating socket connection to $serverUrl")
        SocketManager.connect(this, serverUrl, deviceId, deviceToken, { cmd ->
            scope.launch { handleCommand(cmd) }
        }, {
            // Callback: Registered
            Log.i(TAG, "Socket registered successfully. Performing initial sync.")
            scope.launch { 
                flushOfflineLocations()
                sendMetrics()
                sendLocation(forceHighAccuracy = false)
            }
        })

        // Metrics loop (adaptive frequency)
        scope.launch {
            while (isActive) {
                try {
                    withWakeLock {
                        sendMetrics()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Metrics error: ${e.message}")
                }
                
                val battery = MetricsHelper.getBatteryPercent(this@AgentService) ?: 100
                val interval = if (battery < AgentConfig.LOW_BATTERY_THRESHOLD) 
                    AgentConfig.METRICS_INTERVAL_MS * 5 // 5 min if low
                else 
                    AgentConfig.METRICS_INTERVAL_MS
                    
                delay(interval)
            }
        }

        // Location loop (adaptive based on battery and movement)
        scope.launch {
            while (isActive) {
                try {
                    withWakeLock {
                        sendLocation()
                    }
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

    private suspend inline fun <T> withWakeLock(crossinline block: suspend () -> T): T {
        try {
            wakeLock?.acquire(10_000L) // 10s timeout safety
            return block()
        } finally {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
    }

    fun triggerMetricsUpdate() {
        scope.launch { sendMetrics() }
    }

    fun triggerLocationUpdate(highAccuracy: Boolean = false) {
        scope.launch { sendLocation(highAccuracy) }
    }

    private suspend fun sendMetrics() {
        val metrics = MetricsHelper.collect(this, deviceId)
        SocketManager.emit("agent:metrics", metrics)
        Log.d(TAG, "Metrics sent: CPU=${metrics.optDouble("cpu_percent")}%")
    }

    private suspend fun sendLocation(forceHighAccuracy: Boolean = false) {
        val loc = locationHelper?.getLastLocation(forceHighAccuracy) ?: return
        val data = JSONObject().apply {
            put("deviceId",       deviceId)
            put("latitude",       loc.latitude)
            put("longitude",      loc.longitude)
            put("accuracy_meters", loc.accuracy)
            put("recorded_at",    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date()))
        }

        if (SocketManager.isConnected()) {
            SocketManager.emit("agent:location", data)
            Log.d(TAG, "Location sent: ${loc.latitude}, ${loc.longitude}")
        } else {
            LocationCache.save(this, data)
            Log.w(TAG, "Device offline — location cached locally")
        }
    }

    private suspend fun flushOfflineLocations() {
        val cachedRows = LocationCache.getAll(this)
        if (cachedRows.isEmpty()) return

        Log.i(TAG, "Flushing ${cachedRows.size} offline locations to server...")
        for (row in cachedRows) {
            SocketManager.emit("agent:location", row)
            // Small delay to prevent flooding the socket buffer
            delay(100)
        }
        LocationCache.clear(this)
        Log.i(TAG, "Offline location cache cleared")
    }

    private suspend fun handleCommand(cmd: JSONObject) {
        val commandId   = cmd.getString("commandId")
        val commandType = cmd.getString("command_type")
        val params      = cmd.optJSONObject("command_params")

        Log.i(TAG, "Executing command: $commandType")
        val result = CommandHandler.execute(this, commandType, params)

        if (commandType == "PING") {
            // Wake up and refresh everything with high accuracy
            triggerMetricsUpdate()
            triggerLocationUpdate(highAccuracy = true)
        }

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
        ScreenCaptureHelper.stopSession()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try { cm.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}
