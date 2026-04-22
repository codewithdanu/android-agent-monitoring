package com.codewithdanu.deviceagent

import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import android.util.Log

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Singleton Socket.IO manager.
 * Handles connection, registration, and event routing.
 */
object SocketManager {
    private const val TAG = "SocketManager"
    private var socket: Socket? = null

    enum class ConnectionStatus { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    private var statusListener: ((ConnectionStatus, String?) -> Unit)? = null
    var currentStatus = ConnectionStatus.DISCONNECTED
        private set

    private var lastParams: ConnectionParams? = null

    private data class ConnectionParams(
        val context: Context,
        val serverUrl: String,
        val deviceId: String,
        val deviceToken: String,
        val onCommand: (JSONObject) -> Unit,
        val onRegistered: () -> Unit
    )

    fun setStatusListener(listener: (ConnectionStatus, String?) -> Unit) {
        this.statusListener = listener
        listener(currentStatus, null) // Emit current state immediately
    }

    fun connect(
        context: Context,
        serverUrl: String, 
        deviceId: String, 
        deviceToken: String, 
        onCommand: (JSONObject) -> Unit,
        onRegistered: () -> Unit
    ) {
        lastParams = ConnectionParams(context.applicationContext, serverUrl, deviceId, deviceToken, onCommand, onRegistered)
        
        if (socket?.connected() == true || currentStatus == ConnectionStatus.CONNECTING) {
            Log.d(TAG, "Socket already connected or connecting. Skipping.")
            return
        }

        try {
            // Battery Optimization: Fast reconnect initially, then back off
            val opts = IO.Options.builder()
                .setForceNew(true)
                .setReconnection(true)
                .setReconnectionDelay(5000)
                .setReconnectionDelayMax(60000)
                .setReconnectionAttempts(Int.MAX_VALUE)
                .setUpgrade(true)
                .setTimeout(30_000)
                .build()

            socket = IO.socket(serverUrl, opts)
            currentStatus = ConnectionStatus.CONNECTING

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "Connected. Registering device...")
                currentStatus = ConnectionStatus.CONNECTED
                statusListener?.invoke(currentStatus, null)

                val data = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("deviceToken", deviceToken)
                }
                socket?.emit("agent:register", data)
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
                val reason = args.firstOrNull()?.toString()
                Log.d(TAG, "Disconnected: $reason")
                currentStatus = ConnectionStatus.DISCONNECTED
                statusListener?.invoke(currentStatus, reason)
            }

            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val err = args.firstOrNull()?.toString() ?: "Unknown error"
                Log.e(TAG, "Connection error: $err")
                currentStatus = ConnectionStatus.ERROR
                statusListener?.invoke(currentStatus, err)
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
        currentStatus = ConnectionStatus.DISCONNECTED
    }

    fun isConnected(): Boolean = socket?.connected() == true

    fun reconnect() {
        Log.i(TAG, "Forcing reconnection...")
        val s = socket
        if (s == null) {
            val params = lastParams
            if (params != null) {
                Log.i(TAG, "Socket was null, re-initializing with stored params")
                connect(params.context, params.serverUrl, params.deviceId, params.deviceToken, params.onCommand, params.onRegistered)
            } else {
                Log.e(TAG, "Cannot reconnect: No stored parameters")
            }
        } else {
            if (!s.connected()) {
                s.connect()
            }
        }
    }
}
