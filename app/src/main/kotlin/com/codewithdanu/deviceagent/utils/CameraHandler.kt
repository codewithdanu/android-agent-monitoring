package com.codewithdanu.deviceagent.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
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
        saveToGallery: Boolean = true
    ): File? = suspendCoroutine { continuation ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                // We use ImageCapture for background capture
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                // Default to back camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)

                // Create temp file in cache
                val tempFile = File(context.cacheDir, "remote_capture_${System.currentTimeMillis()}.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            Log.d(TAG, "Photo captured successfully: ${tempFile.absolutePath}")
                            
                            if (saveToGallery) {
                                saveToPublicGallery(context, tempFile)
                            }
                            
                            continuation.resume(tempFile)
                        }

                        override fun onError(exc: ImageCaptureException) {
                            Log.e(TAG, "Photo capture failed: ${exc.message}")
                            continuation.resume(null)
                        }
                    }
                )
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
}
