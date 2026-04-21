package com.codewithdanu.deviceagent

import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import android.util.Log

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Singleton Socket.IO manager.
 * Handles connection, registration, and event routing.
 */
object SocketManager {
    private const val TAG = "SocketManager"
    private var socket: Socket? = null

    fun connect(
        context: Context,
        serverUrl: String, 
        deviceId: String, 
        deviceToken: String, 
        onCommand: (JSONObject) -> Unit,
        onRegistered: () -> Unit
    ) {
        if (socket?.connected() == true) return

        try {
            // Battery Optimization: Less frequent pings while staying alive
            val opts = IO.Options.builder()
                .setForceNew(true)
                .setReconnection(true)
                .setReconnectionDelay(10000)
                .setReconnectionDelayMax(30000)
                .setReconnectionAttempts(Int.MAX_VALUE)
                .setUpgrade(true)
                .setTimeout(60_000)
                .build()

            socket = IO.socket(serverUrl, opts)

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "Connected. Registering device...")
                val data = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("deviceToken", deviceToken)
                }
                socket?.emit("agent:register", data)
                
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Connected to server", Toast.LENGTH_SHORT).show()
                }
            }

            socket?.on("agent:registered") {
                Log.d(TAG, "Agent registered with server")
                onRegistered()
            }

            socket?.on("command") { args ->
                val cmd = args[0] as? JSONObject ?: return@on
                Log.d(TAG, "Command received: ${cmd.optString("command_type")}")
                onCommand(cmd)
            }

            socket?.on(Socket.EVENT_DISCONNECT) { args ->
                Log.d(TAG, "Disconnected: ${args.firstOrNull()}")
            }

            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val err = args.firstOrNull()?.toString() ?: "Unknown error"
                Log.e(TAG, "Connection error: $err")
                
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Connection Error: $err", Toast.LENGTH_LONG).show()
                }
            }

            socket?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Socket setup failed: ${e.message}")
        }
    }

    fun emit(event: String, data: JSONObject) {
        if (socket?.connected() == true) {
            socket?.emit(event, data)
        } else {
            android.util.Log.w(TAG, "Tried to emit '$event' but socket not connected")
        }
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
    }

    fun isConnected(): Boolean = socket?.connected() == true

    fun reconnect() {
        Log.i(TAG, "Forcing reconnection...")
        socket?.disconnect()
        socket?.connect()
    }
}
