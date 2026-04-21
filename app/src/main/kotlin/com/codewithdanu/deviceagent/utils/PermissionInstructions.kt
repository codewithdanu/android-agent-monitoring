package com.codewithdanu.deviceagent.utils

import android.app.AlertDialog
import android.content.Context
import android.os.Build

/**
 * Utility to show step-by-step instructions for complex Android permissions.
 */
object PermissionInstructions {

    fun showRestrictedSettingsTutorial(context: Context, onConfirmed: () -> Unit) {
        val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "Android 13+ has 'Restricted Settings' for security.\n\n" +
            "Steps to Fix:\n" +
            "1. Click 'GO TO SETTINGS' below.\n" +
            "2. Tap the 3-DOTS (⋮) in the top-right corner.\n" +
            "3. Select 'Allow restricted settings'.\n" +
            "4. Enter your PIN/Fingerprint.\n\n" +
            "Once done, come back here to enable the monitoring service."
        } else {
            "Open 'App Info' and ensure all permissions are granted."
        }

        AlertDialog.Builder(context)
            .setTitle("🔓 Restricted Settings Tutorial")
            .setMessage(message)
            .setPositiveButton("GO TO SETTINGS") { _, _ -> onConfirmed() }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    fun showAccessibilityTutorial(context: Context, onConfirmed: () -> Unit) {
        val message = "To monitor active apps, you must enable the Accessibility Service.\n\n" +
            "Steps:\n" +
            "1. Click 'OPEN SETTINGS'.\n" +
            "2. Find 'Downloaded apps' or 'Installed services'.\n" +
            "3. Select 'Device Agent'.\n" +
            "4. Turn the toggle ON.\n\n" +
            "*Note: If it's grayed out, use 'Fix Restricted Settings' first."

        AlertDialog.Builder(context)
            .setTitle("🔍 Activity Monitor Setup")
            .setMessage(message)
            .setPositiveButton("OPEN SETTINGS") { _, _ -> onConfirmed() }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    fun showScreenCaptureInfo(context: Context, onConfirmed: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("📸 Screen Capture Session")
            .setMessage("This permission allows the dashboard to take a remote screenshot of your device.\n\nA system dialog will appear. Please select 'Start now' or 'Accept' to save this preference.")
            .setPositiveButton("OK, START") { _, _ -> onConfirmed() }
            .setNegativeButton("CANCEL", null)
            .show()
    }
}
