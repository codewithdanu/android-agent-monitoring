package com.codewithdanu.deviceagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.NetworkType
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
            Intent.ACTION_USER_PRESENT,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )

        if (intent.action in actions || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i("BootReceiver", "Startup trigger detected: ${intent.action}")
            
            val appContext = context.applicationContext
            
            // 1. Direct aggressive start
            try {
                val serviceIntent = Intent(appContext, AgentService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    appContext.startForegroundService(serviceIntent)
                } else {
                    appContext.startService(serviceIntent)
                }
                Log.d("BootReceiver", "Service started directly from receiver")
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to start service directly: ${e.message}")
            }

            // 2. WorkManager backup (ensures it starts even if direct start is throttled)
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val workRequest = OneTimeWorkRequestBuilder<BootWorker>()
                    .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setConstraints(constraints)
                    .addTag("BOOT_RECOVERY")
                    .build()
                
                WorkManager.getInstance(appContext).enqueue(workRequest)
                Log.d("BootReceiver", "BootWorker backup enqueued")
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to enqueue BootWorker: ${e.message}")
            }
        }
    }
}
