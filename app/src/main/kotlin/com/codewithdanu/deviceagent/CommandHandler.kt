package com.codewithdanu.deviceagent

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.util.Log
import com.codewithdanu.deviceagent.network.NetworkClient
import com.codewithdanu.deviceagent.utils.CameraHandler
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CompletableDeferred

/**
 * Handles commands received from the server.
 */
object CommandHandler {
    private const val TAG = "CommandHandler"

    suspend fun execute(service: AgentService, commandType: String, params: JSONObject?): JSONObject {
        Log.i(TAG, "Executing: $commandType")
        return try {
            when (commandType) {
                "LOCK_SCREEN"  -> lockScreen(service)
                "RING_ALARM"   -> ringAlarm(service)
                "PING", "HEARTBEAT" -> {
                    service.triggerMetricsUpdate()
                    service.triggerLocationUpdate()
                    JSONObject().put("status", "ALIVE").put("timestamp", System.currentTimeMillis())
                }
                "GET_LOCATION" -> {
                    service.triggerLocationUpdate()
                    JSONObject().put("message", "Location update triggered")
                }
                "GET_BATTERY", "GET_SYSTEM_INFO" -> {
                    service.triggerMetricsUpdate()
                    JSONObject().put("message", "Metrics update triggered")
                }
                "LIST_FILES" -> listFiles(service, params)
                "TAKE_PHOTO" -> takePhoto(service, params)
                "RECORD_VIDEO" -> recordVideo(service, params)
                "UPLOAD_FILE" -> uploadFile(service, params)
                "CAPTURE_SCREEN" -> captureScreen(service)
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
            val path = params?.optString("directory", "/sdcard") ?: "/sdcard"
            val dir = java.io.File(path)
            
            if (!dir.exists()) {
                return JSONObject().put("error", "Directory does not exist: $path")
            }
            if (!dir.isDirectory) {
                return JSONObject().put("error", "Not a directory: $path")
            }

            val fileList = dir.listFiles()
            if (fileList == null) {
                return JSONObject().put("error", "Permission Denied: Cannot list files in $path. Try activating 'All Files Access' in app settings.")
            }

            val files = fileList.map { f ->
                org.json.JSONObject().apply {
                    put("name", f.name)
                    put("path", f.absolutePath)
                    put("size", if (f.isFile) f.length() else 0)
                    put("isDirectory", f.isDirectory)
                    put("modified", f.lastModified())
                }
            }
            
            JSONObject().apply {
                put("directory", path)
                put("items", org.json.JSONArray(files))
            }
        } catch (e: Exception) {
            Log.e(TAG, "listFiles error: ${e.message}")
            JSONObject().put("error", "System error: ${e.message}")
        }
    }

    private suspend fun takePhoto(service: AgentService, params: JSONObject?): JSONObject {
        // Sequential capture to avoid camera resource conflicts
        val backPhoto = CameraHandler.takePhoto(service, service, cameraFacing = "back")
        val frontPhoto = CameraHandler.takePhoto(service, service, cameraFacing = "front")
        
        var message = ""
        var successCount = 0

        if (backPhoto != null) {
            val res = doUpload(service, backPhoto)
            if (res.has("message")) {
                message += "Back photo uploaded. "
                successCount++
            }
            try { backPhoto.delete() } catch (_: Exception) {}
        }

        if (frontPhoto != null) {
            val res = doUpload(service, frontPhoto)
            if (res.has("message")) {
                message += "Front photo uploaded."
                successCount++
            }
            try { frontPhoto.delete() } catch (_: Exception) {}
        }

        if (successCount == 0) {
            return JSONObject().put("error", "Failed to capture or upload any photos.")
        }

        return JSONObject().put("message", message.trim())
    }

    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private suspend fun recordVideo(service: AgentService, params: JSONObject?): JSONObject {
        // Sequential 5-second video captures
        val backVideo = CameraHandler.recordVideo(service, service, cameraFacing = "back", durationMs = 8000L)
        val frontVideo = CameraHandler.recordVideo(service, service, cameraFacing = "front", durationMs = 8000L)
        
        var message = ""
        var successCount = 0

        if (backVideo != null) {
            val res = doUpload(service, backVideo)
            if (res.has("message")) {
                message += "Back video uploaded. "
                successCount++
            }
            try { backVideo.delete() } catch (_: Exception) {}
        }

        if (frontVideo != null) {
            val res = doUpload(service, frontVideo)
            if (res.has("message")) {
                message += "Front video uploaded."
                successCount++
            }
            try { frontVideo.delete() } catch (_: Exception) {}
        }

        if (successCount == 0) {
            return JSONObject().put("error", "Failed to record or upload any videos.")
        }

        return JSONObject().put("message", message.trim())
    }

    private suspend fun uploadFile(service: AgentService, params: JSONObject?): JSONObject {
        val path = params?.optString("file_path") 
            ?: return JSONObject().put("error", "file_path is required")
        val file = File(path)
        if (!file.exists()) return JSONObject().put("error", "File not found at $path")
        return doUpload(service, file)
    }

    private suspend fun doUpload(context: Context, file: File): JSONObject {
        val prefs = AgentConfig.getPrefs(context)
        val deviceId = prefs.getString(AgentConfig.KEY_DEVICE_ID, "") ?: ""

        val deviceIdBody = deviceId.toRequestBody("text/plain".toMediaTypeOrNull())
        
        // Dynamic MIME type detection
        val extension = MimeTypeMap.getFileExtensionFromUrl(file.path)
        val mimeType = if (extension != null) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        } else null
        
        val contentType = mimeType ?: "application/octet-stream"
        val fileBody = file.asRequestBody(contentType.toMediaTypeOrNull())
        val filePart = MultipartBody.Part.createFormData("file", file.name, fileBody)

        val api = NetworkClient.getApiService(context)
        val response = api.uploadFile(deviceIdBody, filePart)

        return if (response.isSuccessful) {
            JSONObject().put("message", "File uploaded successfully")
                .put("file_name", file.name)
        } else {
            val errorMsg = response.errorBody()?.string() ?: "Unknown error"
            JSONObject().put("error", "Upload failed: $errorMsg")
        }
    }
    private suspend fun captureScreen(service: AgentService): JSONObject {
        if (!ScreenCaptureHelper.hasPermission()) {
            // Need to request permission via activity
            ScreenCaptureHelper.requestPermission(service)
            return JSONObject().put("error", "Screen Capture permission not granted. Please enable it in the app first.")
        }

        val resultDeferred = CompletableDeferred<Pair<File?, String?>>()
        ScreenCaptureHelper.capture(service) { file, errorMsg ->
            resultDeferred.complete(Pair(file, errorMsg))
        }

        val (file, error) = resultDeferred.await()
        return if (file != null) {
            val res = doUpload(service, file)
            file.delete()
            res
        } else {
            JSONObject().put("error", error ?: "Failed to capture screen.")
        }
    }
}
