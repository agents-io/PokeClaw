// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * ConfigServer lifecycle management singleton
 */
object ConfigServerManager {

    private const val TAG = "ConfigServerManager"
    private const val MAX_PORT_RETRY = 10

    @Volatile
    private var server: ConfigServer? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var appContext: Context? = null

    /** Notification emitted after H5 page saves config; Settings page can observe this Flow to refresh UI */
    private val _configChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val configChanged: SharedFlow<Unit> = _configChanged.asSharedFlow()

    fun notifyConfigChanged() {
        _configChanged.tryEmit(Unit)
    }

    /**
     * Start the configuration server. Requires a WiFi connection.
     */
    fun start(context: Context): Boolean {
        val ctx = context.applicationContext
        appContext = ctx

        if (!isWifiConnected(ctx)) {
            XLog.e(TAG, "Cannot start ConfigServer: WiFi not connected")
            return false
        }

        if (isRunning()) return true

        for (port in ConfigServer.PORT until ConfigServer.PORT + MAX_PORT_RETRY) {
            try {
                val s = ConfigServer(ctx, port)
                s.start()
                server = s
                XLog.i(TAG, "ConfigServer started on port $port")
                registerNetworkCallback(ctx)
                return true
            } catch (e: Exception) {
                XLog.e(TAG, "Port $port unavailable: ${e.message}")
            }
        }
        XLog.e(TAG, "Failed to start ConfigServer: all ports ${ConfigServer.PORT}-${ConfigServer.PORT + MAX_PORT_RETRY - 1} unavailable")
        return false
    }

    fun stop() {
        unregisterNetworkCallback()
        try {
            server?.stop()
        } catch (e: Exception) {
            XLog.e(TAG, "Error stopping ConfigServer: ${e.message}")
        }
        server = null
        XLog.i(TAG, "ConfigServer stopped")
    }

    fun isRunning(): Boolean = server?.isAlive == true

    /**
     * Get the LAN access address, e.g. 192.168.1.100:9527
     * Port is read from the running server instance
     */
    fun getAddress(): String? {
        val ip = getWifiIpAddress(appContext ?: return null) ?: return null
        val port = server?.listeningPort ?: return null
        return "$ip:$port"
    }

    /**
     * Call on app start: auto-starts if it was enabled last time
     */
    fun autoStartIfNeeded(context: Context) {
        if (KVUtils.hasLlmConfig() && KVUtils.isConfigServerEnabled()) {
            start(context)
        }
    }

    /**
     * Check whether there is an active WiFi connection
     */
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Get WiFi IP address via WifiManager (preferred), fall back to NetworkInterface
     */
    private fun getWifiIpAddress(context: Context): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ipInt = wifiInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                val ip = String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
                if (ip != "0.0.0.0") return ip
            }
        } catch (e: Exception) {
            XLog.e(TAG, "WifiManager IP failed: ${e.message}")
        }
        // Fallback method
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        } catch (e: Exception) {
            XLog.e(TAG, "NetworkInterface IP failed: ${e.message}")
            null
        }
    }

    /**
     * Register network change listener. Stops the server when WiFi disconnects, auto-restarts when reconnected (IP may change).
     */
    private fun registerNetworkCallback(context: Context) {
        unregisterNetworkCallback()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                XLog.i(TAG, "WiFi lost, stopping ConfigServer")
                try { server?.stop() } catch (_: Exception) {}
                server = null
                // Do not clear enabled state; auto-restart when WiFi recovers
                _configChanged.tryEmit(Unit)
            }

            override fun onAvailable(network: Network) {
                XLog.i(TAG, "WiFi available, restarting ConfigServer")
                // IP may change after WiFi reconnect, restart the server
                if (KVUtils.isConfigServerEnabled() && !isRunning()) {
                    val ctx = appContext ?: return
                    for (port in ConfigServer.PORT until ConfigServer.PORT + MAX_PORT_RETRY) {
                        try {
                            val s = ConfigServer(ctx, port)
                            s.start()
                            server = s
                            XLog.i(TAG, "ConfigServer restarted on port $port")
                            break
                        } catch (e: Exception) {
                            XLog.e(TAG, "Port $port unavailable on restart: ${e.message}")
                        }
                    }
                    _configChanged.tryEmit(Unit)
                }
            }
        }

        cm.registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        try {
            val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to unregister network callback: ${e.message}")
        }
        networkCallback = null
    }
}
