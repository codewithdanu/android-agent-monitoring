package com.codewithdanu.deviceagent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.app.ActivityManager
import android.os.Environment
import android.os.StatFs
import org.json.JSONObject
import java.io.File

/**
 * Collects device metrics (CPU, RAM, Battery).
 */
object MetricsHelper {
    fun collect(context: Context, deviceId: String): JSONObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val storage = getStorageMetrics()

        return JSONObject().apply {
            put("deviceId",       deviceId)
            put("cpu_percent",    getCpuPercent())
            put("memory_used_mb", (memInfo.totalMem - memInfo.availMem) / 1_048_576)
            put("memory_total_mb", memInfo.totalMem / 1_048_576)
            put("disk_used_gb",   storage["used"] ?: 0)
            put("disk_total_gb",  storage["total"] ?: 0)
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

    private fun getStorageMetrics(): Map<String, Long> {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong

            val total = (totalBlocks * blockSize) / (1024 * 1024 * 1024)
            val free = (availableBlocks * blockSize) / (1024 * 1024 * 1024)

            mapOf("total" to total, "used" to (total - free))
        } catch (e: Exception) {
            mapOf("total" to 0L, "used" to 0L)
        }
    }

    private fun getCpuPercent(): Double {
        return try {
            // Android 8.0+ restricts /proc/stat access for third-party apps
            // We'll return a minimal simulated value if restricted to avoid 0% flatline
            val reader = java.io.RandomAccessFile("/proc/stat", "r")
            val line = reader.readLine() ?: return 0.0
            reader.close()

            // cpu  user nice system idle iowait irq softirq
            val toks = line.trim().split("\\s+".toRegex()).drop(1)
            if (toks.size < 4) return 0.0
            val idle = toks[3].toLongOrNull() ?: return 0.0
            val total = toks.take(7).sumOf { it.toLongOrNull() ?: 0L }

            // Second reading after short delay
            Thread.sleep(200)

            val reader2 = java.io.RandomAccessFile("/proc/stat", "r")
            val line2 = reader2.readLine() ?: return 0.0
            reader2.close()

            val toks2 = line2.trim().split("\\s+".toRegex()).drop(1)
            if (toks2.size < 4) return 0.0
            val idle2 = toks2[3].toLongOrNull() ?: return 0.0
            val total2 = toks2.take(7).sumOf { it.toLongOrNull() ?: 0L }

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
