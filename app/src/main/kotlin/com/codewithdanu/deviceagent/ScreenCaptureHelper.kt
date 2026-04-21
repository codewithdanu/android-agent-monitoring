package com.codewithdanu.deviceagent

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Helper to capture the device screen using MediaProjection API.
 * Refactored to support persistent sessions for Android 14+ compatibility.
 */
object ScreenCaptureHelper {
    private const val TAG = "ScreenCapture"
    
    private var projectionResultCode: Int = -1
    private var projectionData: Intent? = null
    
    // Persistent Session components
    private var activeProjection: MediaProjection? = null
    private var activeVirtualDisplay: VirtualDisplay? = null
    private var activeImageReader: ImageReader? = null
    private var isCapturing = false
    
    fun hasPermission(): Boolean = projectionData != null

    fun onPermissionResult(context: Context, resultCode: Int, data: Intent) {
        projectionResultCode = resultCode
        projectionData = data
        Log.i(TAG, "Screen capture permission granted and stored")
        
        // CRITICAL: Use applicationContext to ensure the session isn't tied to the Activity's lifecycle
        val appContext = context.applicationContext
        stopSession()
        setupSession(appContext, data)
    }

    @SuppressLint("WrongConstant")
    fun capture(context: Context, callback: (File?) -> Unit) {
        val data = projectionData
        if (data == null) {
            Log.e(TAG, "No projection data available. Must request permission first.")
            callback(null)
            return
        }

        // Initialize persistent session if it's not already running
        if (activeProjection == null || activeVirtualDisplay == null) {
            if (!setupSession(context, data)) {
                callback(null)
                return
            }
            // Give it a moment to initialize
            Handler(Looper.getMainLooper()).postDelayed({
                performFrameCapture(context, callback)
            }, 1000)
        } else {
            // Session is already running, capture immediately
            performFrameCapture(context, callback)
        }
    }

    private fun setupSession(context: Context, data: Intent): Boolean {
        try {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = projectionManager.getMediaProjection(projectionResultCode, data) ?: return false
            
            // Android 14+ requires registering a callback BEFORE creating virtual display
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.i(TAG, "MediaProjection session was stopped by system")
                    stopSession()
                }
            }, Handler(Looper.getMainLooper()))

            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            // Using 2 buffers for smoothness
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            // Set listener to drain frames in the background to keep the session alive
            imageReader.setOnImageAvailableListener({ reader ->
                if (isCapturing) return@setOnImageAvailableListener
                
                try {
                    // Just acquire and close to keep the buffer empty
                    reader.acquireLatestImage()?.close()
                } catch (e: Exception) {
                    // Ignore errors during draining
                }
            }, Handler(Looper.getMainLooper()))

            val virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, null
            )

            activeProjection = mediaProjection
            activeVirtualDisplay = virtualDisplay
            activeImageReader = imageReader
            
            Log.i(TAG, "Persistent screen capture session initialized ($width x $height)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup persistent session: ${e.message}")
            // CRITICAL: On Android 14+, if setup fails, the token is often invalidated.
            // We MUST clear the stored permission data so the next attempt triggers a fresh request.
            projectionData = null
            projectionResultCode = -1
            stopSession()
            return false
        }
    }

    private fun performFrameCapture(context: Context, callback: (File?) -> Unit) {
        val reader = activeImageReader
        if (reader == null) {
            callback(null)
            return
        }

        try {
            isCapturing = true
            // Acquiring the latest image from the stream
            val image = reader.acquireLatestImage()
            
            if (image == null) {
                isCapturing = false
                Log.d(TAG, "Image was null (draining might be too fast)")
                callback(null)
                return
            }

            processImage(context, image) { result ->
                isCapturing = false
                callback(result)
            }
        } catch (e: Exception) {
            isCapturing = false
            Log.e(TAG, "Error acquiring image: ${e.message}")
            callback(null)
        }
    }

    private fun processImage(context: Context, image: android.media.Image, callback: (File?) -> Unit) {
        try {
            val width = image.width
            val height = image.height
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            // Crop padding if necessary
            val finalBitmap = if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, width, height)
            } else bitmap

            val file = File(context.cacheDir, "screenshot_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) // Slightly lower quality for faster transmission
            }
            
            Log.i(TAG, "Screenshot captured from active session: ${file.absolutePath}")
            callback(file)
        } catch (e: Exception) {
            Log.e(TAG, "Image processing failed: ${e.message}")
            image.close()
            callback(null)
        }
    }

    fun stopSession() {
        Log.i(TAG, "Stopping persistent screen capture session")
        try {
            activeVirtualDisplay?.release()
            activeProjection?.stop()
            activeImageReader?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up session: ${e.message}")
        } finally {
            activeVirtualDisplay = null
            activeProjection = null
            activeImageReader = null
            
            // If the system stopped us (MediaProjection.Callback.onStop), 
            // we MUST clear the token because it's no longer reusable.
            projectionData = null
            projectionResultCode = -1
        }
    }

    fun requestPermission(context: Context) {
        val intent = Intent(context, CaptureScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        // On Android 14+, we must explicitly allow background activity start via options
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val options = ActivityOptions.makeBasic()
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            context.startActivity(intent, options.toBundle())
        } else {
            context.startActivity(intent)
        }
    }
}
