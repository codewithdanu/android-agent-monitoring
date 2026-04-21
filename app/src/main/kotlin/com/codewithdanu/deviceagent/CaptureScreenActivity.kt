package com.codewithdanu.deviceagent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

/**
 * Invisible activity to handle the MediaProjection permission dialog.
 */
class CaptureScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i("CaptureActivity", "Requesting screen capture permission")
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE)
        } catch (e: Exception) {
            Log.e("CaptureActivity", "Failed to start permission intent: ${e.message}")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                Log.i("CaptureActivity", "Permission GRANTED")
                ScreenCaptureHelper.onPermissionResult(this, resultCode, data)
                
                // Delay finish slightly to allow session to stabilize
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    finish()
                }, 500)
                return
            } else {
                Log.w("CaptureActivity", "Permission DENIED or CANCELLED")
                finish()
            }
        }
    }

    companion object {
        private const val REQUEST_CODE = 4242
    }
}
