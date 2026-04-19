package com.codewithdanu.deviceagent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.app.ActivityManager
import org.json.JSONObject

/**
 * Collects device metrics (CPU, RAM, Battery).
 */
object MetricsHelper {

    fun collect(context: Context, deviceId: String): JSONObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        return JSONObject().apply {
            put("deviceId",       deviceId)
            put("cpu_percent",    getCpuPercent())
            put("memory_used_mb", (memInfo.totalMem - memInfo.availMem) / 1_048_576)
            put("memory_total_mb", memInfo.totalMem / 1_048_576)
            put("battery_percent", getBatteryPercent(context))
            put("timestamp",      System.currentTimeMillis())
        }
    }

    fun getBatteryPercent(context: Context): Int? {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return null
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (scale > 0) (level * 100 / scale) else null
    }

    private fun getCpuPercent(): Double {
        return try {
            // Read /proc/stat for accurate CPU usage
            val reader = java.io.RandomAccessFile("/proc/stat", "r")
            val line = reader.readLine()
            reader.close()

            // cpu  user nice system idle iowait irq softirq
            val toks = line.split("\\s+".toRegex()).drop(1)
            val idle = toks[3].toLong()
            val total = toks.take(7).sumOf { it.toLong() }

            // Second reading after short delay
            Thread.sleep(200)

            val reader2 = java.io.RandomAccessFile("/proc/stat", "r")
            val line2 = reader2.readLine()
            reader2.close()

            val toks2 = line2.split("\\s+".toRegex()).drop(1)
            val idle2 = toks2[3].toLong()
            val total2 = toks2.take(7).sumOf { it.toLong() }

            val diffTotal = total2 - total
            val diffIdle = idle2 - idle

            if (diffTotal > 0) {
                ((diffTotal - diffIdle).toDouble() / diffTotal * 100).coerceIn(0.0, 100.0)
            } else 0.0
        } catch (e: Exception) {
            0.0
        }
    }
}
