package com.codewithdanu.deviceagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Triggered on device boot.
 * Schedules a BootWorker to ensure the service starts reliably.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val actions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )

        if (intent.action in actions) {
            Log.i("BootReceiver", "Boot action detected: ${intent.action}")
            
            // Using WorkManager to start the service is more reliable on aggressive OEMs
            val workRequest = OneTimeWorkRequestBuilder<BootWorker>().build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}
