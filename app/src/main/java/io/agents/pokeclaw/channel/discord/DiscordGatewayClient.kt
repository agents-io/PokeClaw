// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.discord

import android.os.Handler
import android.os.Looper
import io.agents.pokeclaw.utils.XLog
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Discord Gateway WebSocket 客户端
 * 参照 QBotWebSocketManager 实现，协议与 QQ Bot Gateway 高度相似
 */
class DiscordGatewayClient private constructor() {

    companion object {
        private const val TAG = "DiscordGateway"

        @Volatile
        private var instance: DiscordGatewayClient? = null

        @JvmStatic
        fun getInstance(): DiscordGatewayClient {
            return instance ?: synchronized(this) {
                instance ?: DiscordGatewayClient().also { instance = it }
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val heartbeatHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var heartbeatInterval: Long = 0
    @Volatile
    private var sessionId: String? = null
    @Volatile
    private var resumeGatewayUrl: String? = null
    @Volatile
    private var lastSeq: Int? = null
    @Volatile
    private var botToken: String? = null
    @Volatile
    private var isConnected = false
    @Volatile
    private var heartbeatAckReceived = true
    @Volatile
    private var stopped = false

    private var messageListener: OnDiscordMessageListener? = null
    private val connectionStateListeners = CopyOnWriteArrayList<ConnectionStateListener>()

    interface OnDiscordMessageListener {
        fun onDiscordMessage(channelId: String, messageId: String, content: String)
    }

    interface ConnectionStateListener {
        fun onConnectionStateChanged(connected: Boolean)
    }

    fun setOnDiscordMessageListener(listener: OnDiscordMessageListener?) {
        this.messageListener = listener
    }

    fun addConnectionStateListener(listener: ConnectionStateListener) {
        if (!connectionStateListeners.contains(listener)) {
            connectionStateListeners.add(listener)
        }
    }

    fun removeConnectionStateListener(listener: ConnectionStateListener) {
        connectionStateListeners.remove(listener)
    }

    private fun notifyConnectionStateChanged(connected: Boolean) {
        mainHandler.post {
            for (listener in connectionStateListeners) {
                listener.onConnectionStateChanged(connected)
            }
        }
    }

    /**
     * 使用 Bot Token 启动 Gateway 连接
     */
    fun start(token: String) {
        stopped = false
        this.botToken = token
        connectWebSocket(DiscordConstants.GATEWAY_URL)
    }

    /**
     * 关闭连接，停止一切重连
     */
    fun stop() {
        stopped = true
        mainHandler.removeCallbacksAndMessages(null)
        heartbeatHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "关闭连接")
        webSocket = null
        isConnected = false
        sessionId = null
        lastSeq = null
        resumeGatewayUrl = null
        notifyConnectionStateChanged(false)
    }

    private fun connectWebSocket(url: String) {
        val request = Request.Builder().url(url).build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                XLog.d(TAG, "WebSocket连接成功")
                isConnected = true
                notifyConnectionStateChanged(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWebSocketMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                XLog.d(TAG, "收到二进制消息, 长度=${bytes.size}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                XLog.w(TAG, "WebSocket关闭中: code=$code, reason=$reason")
                isConnected = false
                notifyConnectionStateChanged(false)
                handleWebSocketClose(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                XLog.w(TAG, "WebSocket已关闭: code=$code, reason=$reason")
                isConnected = false
                notifyConnectionStateChanged(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                XLog.e(TAG, "WebSocket连接失败: ${t.message}")
                isConnected = false
                notifyConnectionStateChanged(false)
                if (!stopped) {
                    mainHandler.postDelayed({ reconnect() }, 5000)
                }
            }
        })
    }

    private fun handleWebSocketMessage(message: String) {
        try {
            val json = gson.fromJson(message, JsonObject::class.java)
            val op = json.get("op").asInt
            val t = json.get("t")?.takeIf { !it.isJsonNull }?.asString
            val s = json.get("s")?.takeIf { !it.isJsonNull }?.asInt

            if (s != null) {
                lastSeq = s
            }

            when (op) {
                DiscordConstants.OP_HELLO -> handleHello(json)
                DiscordConstants.OP_DISPATCH -> handleDispatch(t, json)
                DiscordConstants.OP_HEARTBEAT_ACK -> {
                    heartbeatAckReceived = true
                    XLog.d(TAG, "收到心跳响应")
                }
                DiscordConstants.OP_HEARTBEAT -> {
                    // 服务器请求立即发送心跳
                    sendHeartbeat()
                }
                DiscordConstants.OP_RECONNECT -> {
                    XLog.w(TAG, "收到重连请求(OP=7)")
                    webSocket?.close(1000, "服务器要求重连")
                    webSocket = null
                    heartbeatHandler.removeCallbacksAndMessages(null)
                    isConnected = false
                    notifyConnectionStateChanged(false)
                    mainHandler.postDelayed({ reconnect() }, 1000)
                }
                DiscordConstants.OP_INVALID_SESSION -> {
                    val resumable = json.get("d")?.asBoolean ?: false
                    if (resumable && sessionId != null) {
                        XLog.d(TAG, "会话可恢复，尝试Resume")
                        mainHandler.postDelayed({ sendResume() }, 2000)
                    } else {
                        XLog.d(TAG, "会话无效，重新Identify")
                        sessionId = null
                        lastSeq = null
                        mainHandler.postDelayed({ sendIdentify() }, 5000)
                    }
                }
                else -> XLog.w(TAG, "未知OpCode: $op")
            }
        } catch (e: Exception) {
            XLog.e(TAG, "解析WebSocket消息失败: ${e.message}", e)
        }
    }

    private fun handleHello(json: JsonObject) {
        val d = json.getAsJsonObject("d")
        heartbeatInterval = d.get("heartbeat_interval").asLong
        XLog.d(TAG, "心跳间隔: ${heartbeatInterval}ms")

        startHeartbeat()

        // 如果有 sessionId，尝试 Resume；否则 Identify
        if (sessionId != null && lastSeq != null) {
            sendResume()
        } else {
            sendIdentify()
        }
    }

    private fun handleDispatch(eventType: String?, json: JsonObject) {
        when (eventType) {
            DiscordConstants.EVENT_READY -> {
                val d = json.getAsJsonObject("d")
                sessionId = d.get("session_id").asString
                // Discord 返回一个 resume_gateway_url 供断线重连使用
                resumeGatewayUrl = d.get("resume_gateway_url")?.takeIf { !it.isJsonNull }?.asString
                XLog.d(TAG, "Gateway就绪, sessionId=$sessionId, resumeUrl=$resumeGatewayUrl")
            }
            DiscordConstants.EVENT_RESUMED -> {
                XLog.d(TAG, "连接已恢复")
            }
            DiscordConstants.EVENT_MESSAGE_CREATE -> {
                handleMessageCreate(json)
            }
            else -> {
                XLog.d(TAG, "未处理的事件: $eventType")
            }
        }
    }

    private fun handleMessageCreate(json: JsonObject) {
        try {
            val d = json.getAsJsonObject("d")
            // 忽略 Bot 自己发的消息
            val author = d.getAsJsonObject("author")
            val isBot = author?.get("bot")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            if (isBot) return

            val channelId = d.get("channel_id").asString
            val messageId = d.get("id").asString
            val content = d.get("content")?.takeIf { !it.isJsonNull }?.asString ?: ""

            XLog.d(TAG, "收到消息: channelId=$channelId, messageId=$messageId, content=$content")

            messageListener?.onDiscordMessage(channelId, messageId, content)
        } catch (e: Exception) {
            XLog.e(TAG, "处理 MESSAGE_CREATE 失败: ${e.message}", e)
        }
    }

    private fun startHeartbeat() {
        heartbeatHandler.removeCallbacksAndMessages(null)
        heartbeatAckReceived = true
        // Discord 建议首次心跳在 heartbeat_interval * jitter (0~1) 之后发送
        val jitterDelay = (heartbeatInterval * Math.random()).toLong()
        heartbeatHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isConnected) return
                if (!heartbeatAckReceived) {
                    XLog.w(TAG, "心跳超时：未收到上次心跳ACK，断开重连")
                    webSocket?.close(1000, "心跳超时")
                    webSocket = null
                    heartbeatHandler.removeCallbacksAndMessages(null)
                    isConnected = false
                    notifyConnectionStateChanged(false)
                    mainHandler.postDelayed({ reconnect() }, 1000)
                    return
                }
                heartbeatAckReceived = false
                sendHeartbeat()
                heartbeatHandler.postDelayed(this, heartbeatInterval)
            }
        }, jitterDelay)
    }

    private fun sendHeartbeat() {
        val payload = JsonObject().apply {
            addProperty("op", DiscordConstants.OP_HEARTBEAT)
            if (lastSeq != null) {
                addProperty("d", lastSeq)
            } else {
                add("d", null)
            }
        }
        webSocket?.send(gson.toJson(payload))
    }

    private fun sendIdentify() {
        val token = botToken ?: return
        val payload = JsonObject().apply {
            addProperty("op", DiscordConstants.OP_IDENTIFY)
            add("d", JsonObject().apply {
                addProperty("token", token)
                addProperty("intents",
                    DiscordConstants.INTENT_GUILDS or
                    DiscordConstants.INTENT_GUILD_MESSAGES or
                    DiscordConstants.INTENT_DIRECT_MESSAGES or
                    DiscordConstants.INTENT_MESSAGE_CONTENT
                )
                add("properties", JsonObject().apply {
                    addProperty("os", "android")
                    addProperty("browser", "claw")
                    addProperty("device", "claw")
                })
            })
        }
        XLog.d(TAG, "发送Identify")
        webSocket?.send(gson.toJson(payload))
    }

    private fun sendResume() {
        val token = botToken ?: return
        val sid = sessionId ?: return
        val seq = lastSeq ?: return

        val payload = JsonObject().apply {
            addProperty("op", DiscordConstants.OP_RESUME)
            add("d", JsonObject().apply {
                addProperty("token", token)
                addProperty("session_id", sid)
                addProperty("seq", seq)
            })
        }
        XLog.d(TAG, "发送Resume")
        webSocket?.send(gson.toJson(payload))
    }

    private fun handleWebSocketClose(code: Int, reason: String) {
        heartbeatHandler.removeCallbacksAndMessages(null)

        when (code) {
            DiscordConstants.CLOSE_AUTHENTICATION_FAILED -> {
                XLog.e(TAG, "鉴权失败(4004)，Bot Token 无效，不再重连")
            }
            DiscordConstants.CLOSE_INVALID_INTENTS,
            DiscordConstants.CLOSE_DISALLOWED_INTENTS -> {
                XLog.e(TAG, "Intents 无效或无权限(code=$code)，请检查 Discord Developer Portal 配置")
            }
            DiscordConstants.CLOSE_INVALID_API_VERSION -> {
                XLog.e(TAG, "API 版本无效(4012)，不再重连")
            }
            DiscordConstants.CLOSE_SESSION_TIMED_OUT -> {
                XLog.d(TAG, "Session 超时，可以Resume重连")
                mainHandler.postDelayed({ reconnect() }, 1000)
            }
            DiscordConstants.CLOSE_RATE_LIMITED -> {
                XLog.w(TAG, "发送过快，延迟重连")
                mainHandler.postDelayed({ reconnect() }, 5000)
            }
            DiscordConstants.CLOSE_INVALID_SEQ -> {
                XLog.w(TAG, "Seq 无效，清除会话并重连")
                sessionId = null
                lastSeq = null
                mainHandler.postDelayed({ reconnect() }, 1000)
            }
            1000 -> {
                XLog.d(TAG, "WebSocket正常关闭")
            }
            else -> {
                XLog.w(TAG, "未知关闭码: $code，尝试重连")
                mainHandler.postDelayed({ reconnect() }, 3000)
            }
        }
    }

    private fun reconnect() {
        if (stopped) return
        heartbeatHandler.removeCallbacksAndMessages(null)
        val url = if (sessionId != null && resumeGatewayUrl != null) {
            resumeGatewayUrl!!
        } else {
            DiscordConstants.GATEWAY_URL
        }
        XLog.d(TAG, "重连到: $url")
        connectWebSocket(url)
    }

    fun isConnected(): Boolean = isConnected

    fun getConnectionState(): String {
        return "connected=$isConnected, sessionId=$sessionId, lastSeq=$lastSeq, heartbeatInterval=$heartbeatInterval"
    }
}
