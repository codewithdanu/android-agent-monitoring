package com.codewithdanu.deviceagent

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.util.Log
import org.json.JSONObject

/**
 * Handles commands received from the server.
 */
object CommandHandler {
    private const val TAG = "CommandHandler"

    fun execute(service: AgentService, commandType: String, params: JSONObject?): JSONObject {
        Log.i(TAG, "Executing: $commandType")
        return try {
            when (commandType) {
                "LOCK_SCREEN"  -> lockScreen(service)
                "RING_ALARM"   -> ringAlarm(service)
                "GET_LOCATION" -> {
                    service.triggerLocationUpdate()
                    JSONObject().put("message", "Location update triggered")
                }
                "GET_BATTERY", "GET_SYSTEM_INFO" -> {
                    service.triggerMetricsUpdate()
                    JSONObject().put("message", "Metrics update triggered")
                }
                "LIST_FILES" -> listFiles(service, params)
                else         -> JSONObject().put("error", "Unknown command: $commandType")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: ${e.message}")
            JSONObject().put("error", e.message)
        }
    }

    private fun lockScreen(context: Context): JSONObject {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)

            if (dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
                JSONObject().put("message", "Screen locked")
            } else {
                JSONObject().put("error", "Device Admin not activated. Please activate it in Settings.")
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message)
        }
    }

    private fun ringAlarm(context: Context): JSONObject {
        return try {
            // Set volume to max
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(
                AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )

            // Try alarm URI first, fall back to notification/ringtone
            // Xiaomi/MIUI often has a missing ContentResolver cache for alarms
            val uri = try {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            } catch (e: Exception) { null }
                ?: try {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                } catch (e: Exception) { null }
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            val ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone?.play()

            // Auto-stop after 30 seconds
            if (ringtone != null) {
                Thread {
                    Thread.sleep(30_000)
                    try { if (ringtone.isPlaying) ringtone.stop() } catch (_: Exception) {}
                }.start()
            }

            JSONObject().put("message", "Alarm ringing for 30 seconds")
        } catch (e: Exception) {
            Log.e(TAG, "ringAlarm failed: ${e.message}")
            JSONObject().put("error", e.message)
        }
    }

    private fun listFiles(context: Context, params: JSONObject?): JSONObject {
        return try {
            val path = params?.optString("path", "/sdcard") ?: "/sdcard"
            val dir = java.io.File(path)
            if (!dir.exists() || !dir.isDirectory) {
                return JSONObject().put("error", "Path not found: $path")
            }
            val files = dir.listFiles()?.map { f ->
                org.json.JSONObject().apply {
                    put("name", f.name)
                    put("path", f.absolutePath)
                    put("size", if (f.isFile) f.length() else 0)
                    put("is_dir", f.isDirectory)
                    put("modified", f.lastModified())
                }
            } ?: emptyList()
            JSONObject().apply {
                put("path", path)
                put("files", org.json.JSONArray(files))
            }
        } catch (e: Exception) {
            Log.e(TAG, "listFiles failed: ${e.message}")
            JSONObject().put("error", e.message)
        }
    }
}
