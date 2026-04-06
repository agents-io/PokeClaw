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
 * ConfigServer 生命周期管理单例
 */
object ConfigServerManager {

    private const val TAG = "ConfigServerManager"
    private const val MAX_PORT_RETRY = 10

    @Volatile
    private var server: ConfigServer? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var appContext: Context? = null

    /** H5 页面保存配置后发出通知，Settings 页面可观察此 Flow 来刷新 UI */
    private val _configChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val configChanged: SharedFlow<Unit> = _configChanged.asSharedFlow()

    fun notifyConfigChanged() {
        _configChanged.tryEmit(Unit)
    }

    /**
     * 启动配置服务，必须有 WiFi 连接
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
     * 获取局域网访问地址，如 192.168.1.100:9527
     * 端口从实际运行的 server 实例读取
     */
    fun getAddress(): String? {
        val ip = getWifiIpAddress(appContext ?: return null) ?: return null
        val port = server?.listeningPort ?: return null
        return "$ip:$port"
    }

    /**
     * App 启动时调用：如果上次是开启状态则自动启动
     */
    fun autoStartIfNeeded(context: Context) {
        if (KVUtils.hasLlmConfig() && KVUtils.isConfigServerEnabled()) {
            start(context)
        }
    }

    /**
     * 判断当前是否有 WiFi 连接
     */
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * 通过 WifiManager 获取 WiFi IP 地址（优先），回退到 NetworkInterface
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
        // 回退方案
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
     * 注册网络变化监听，WiFi 断开时停止服务，重连时自动重启（IP 可能变化）
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
                // 不清除 enabled 状态，WiFi 恢复后自动重启
                _configChanged.tryEmit(Unit)
            }

            override fun onAvailable(network: Network) {
                XLog.i(TAG, "WiFi available, restarting ConfigServer")
                // WiFi 重连后 IP 可能变化，重新启动
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
