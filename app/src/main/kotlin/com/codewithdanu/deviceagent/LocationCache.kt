package com.codewithdanu.deviceagent

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Persists locations to a local file when the device is offline.
 * Implementation: simple JSON array in internal storage.
 */
object LocationCache {
    private const val TAG = "LocationCache"
    private const val FILE_NAME = "offline_locations.json"
    private const val MAX_ENTRIES = 1000

    fun save(context: Context, location: JSONObject) {
        val list = getAll(context).toMutableList()
        
        // Add timestamp if missing
        if (!location.has("recorded_at")) {
            location.put("recorded_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date()))
        }

        list.add(location)

        // Limit size
        if (list.size > MAX_ENTRIES) {
            list.removeAt(0)
        }

        saveAll(context, list)
    }

    fun getAll(context: Context): List<JSONObject> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()

        return try {
            val content = file.readText()
            val array = JSONArray(content)
            val result = mutableListOf<JSONObject>()
            for (i in 0 until array.length()) {
                result.add(array.getJSONObject(i))
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read cache: ${e.message}")
            emptyList()
        }
    }

    fun clear(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) {
            file.delete()
        }
    }

    private fun saveAll(context: Context, list: List<JSONObject>) {
        try {
            val array = JSONArray()
            list.forEach { array.put(it) }
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cache: ${e.message}")
        }
    }
}
