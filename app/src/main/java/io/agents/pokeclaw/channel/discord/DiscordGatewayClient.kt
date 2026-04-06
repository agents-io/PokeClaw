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
 * Discord Gateway WebSocket client
 * Based on QBotWebSocketManager; the protocol is highly similar to QQ Bot Gateway
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
     * Start a Gateway connection using the Bot Token
     */
    fun start(token: String) {
        stopped = false
        this.botToken = token
        connectWebSocket(DiscordConstants.GATEWAY_URL)
    }

    /**
     * Close the connection and stop all reconnection attempts
     */
    fun stop() {
        stopped = true
        mainHandler.removeCallbacksAndMessages(null)
        heartbeatHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Connection closed")
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
                XLog.d(TAG, "WebSocket connected")
                isConnected = true
                notifyConnectionStateChanged(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWebSocketMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                XLog.d(TAG, "Binary message received, length=${bytes.size}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                XLog.w(TAG, "WebSocket closing: code=$code, reason=$reason")
                isConnected = false
                notifyConnectionStateChanged(false)
                handleWebSocketClose(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                XLog.w(TAG, "WebSocket closed: code=$code, reason=$reason")
                isConnected = false
                notifyConnectionStateChanged(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                XLog.e(TAG, "WebSocket connection failed: ${t.message}")
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
                    XLog.d(TAG, "Heartbeat ACK received")
                }
                DiscordConstants.OP_HEARTBEAT -> {
                    // Server requests an immediate heartbeat
                    sendHeartbeat()
                }
                DiscordConstants.OP_RECONNECT -> {
                    XLog.w(TAG, "Reconnect request received (OP=7)")
                    webSocket?.close(1000, "Server requested reconnect")
                    webSocket = null
                    heartbeatHandler.removeCallbacksAndMessages(null)
                    isConnected = false
                    notifyConnectionStateChanged(false)
                    mainHandler.postDelayed({ reconnect() }, 1000)
                }
                DiscordConstants.OP_INVALID_SESSION -> {
                    val resumable = json.get("d")?.asBoolean ?: false
                    if (resumable && sessionId != null) {
                        XLog.d(TAG, "Session resumable, attempting Resume")
                        mainHandler.postDelayed({ sendResume() }, 2000)
                    } else {
                        XLog.d(TAG, "Session invalid, re-Identifying")
                        sessionId = null
                        lastSeq = null
                        mainHandler.postDelayed({ sendIdentify() }, 5000)
                    }
                }
                else -> XLog.w(TAG, "Unknown OpCode: $op")
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to parse WebSocket message: ${e.message}", e)
        }
    }

    private fun handleHello(json: JsonObject) {
        val d = json.getAsJsonObject("d")
        heartbeatInterval = d.get("heartbeat_interval").asLong
        XLog.d(TAG, "Heartbeat interval: ${heartbeatInterval}ms")

        startHeartbeat()

        // If sessionId exists, attempt Resume; otherwise Identify
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
                // Discord returns a resume_gateway_url for use when reconnecting after disconnect
                resumeGatewayUrl = d.get("resume_gateway_url")?.takeIf { !it.isJsonNull }?.asString
                XLog.d(TAG, "Gateway ready, sessionId=$sessionId, resumeUrl=$resumeGatewayUrl")
            }
            DiscordConstants.EVENT_RESUMED -> {
                XLog.d(TAG, "Connection resumed")
            }
            DiscordConstants.EVENT_MESSAGE_CREATE -> {
                handleMessageCreate(json)
            }
            else -> {
                XLog.d(TAG, "Unhandled event: $eventType")
            }
        }
    }

    private fun handleMessageCreate(json: JsonObject) {
        try {
            val d = json.getAsJsonObject("d")
            // Ignore messages sent by the bot itself
            val author = d.getAsJsonObject("author")
            val isBot = author?.get("bot")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            if (isBot) return

            val channelId = d.get("channel_id").asString
            val messageId = d.get("id").asString
            val content = d.get("content")?.takeIf { !it.isJsonNull }?.asString ?: ""

            XLog.d(TAG, "Message received: channelId=$channelId, messageId=$messageId, content=$content")

            messageListener?.onDiscordMessage(channelId, messageId, content)
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to handle MESSAGE_CREATE: ${e.message}", e)
        }
    }

    private fun startHeartbeat() {
        heartbeatHandler.removeCallbacksAndMessages(null)
        heartbeatAckReceived = true
        // Discord recommends sending the first heartbeat after heartbeat_interval * jitter (0~1)
        val jitterDelay = (heartbeatInterval * Math.random()).toLong()
        heartbeatHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isConnected) return
                if (!heartbeatAckReceived) {
                    XLog.w(TAG, "Heartbeat timeout: no ACK for last heartbeat, disconnecting to reconnect")
                    webSocket?.close(1000, "Heartbeat timeout")
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
        XLog.d(TAG, "Sending Identify")
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
        XLog.d(TAG, "Sending Resume")
        webSocket?.send(gson.toJson(payload))
    }

    private fun handleWebSocketClose(code: Int, reason: String) {
        heartbeatHandler.removeCallbacksAndMessages(null)

        when (code) {
            DiscordConstants.CLOSE_AUTHENTICATION_FAILED -> {
                XLog.e(TAG, "Auth failed (4004), Bot Token is invalid, no more reconnects")
            }
            DiscordConstants.CLOSE_INVALID_INTENTS,
            DiscordConstants.CLOSE_DISALLOWED_INTENTS -> {
                XLog.e(TAG, "Invalid Intents or no permission (code=$code), please check Discord Developer Portal config")
            }
            DiscordConstants.CLOSE_INVALID_API_VERSION -> {
                XLog.e(TAG, "Invalid API version (4012), no more reconnects")
            }
            DiscordConstants.CLOSE_SESSION_TIMED_OUT -> {
                XLog.d(TAG, "Session expired, Resume reconnect possible")
                mainHandler.postDelayed({ reconnect() }, 1000)
            }
            DiscordConstants.CLOSE_RATE_LIMITED -> {
                XLog.w(TAG, "Sending too fast, delaying reconnect")
                mainHandler.postDelayed({ reconnect() }, 5000)
            }
            DiscordConstants.CLOSE_INVALID_SEQ -> {
                XLog.w(TAG, "Invalid seq, clearing session and reconnecting")
                sessionId = null
                lastSeq = null
                mainHandler.postDelayed({ reconnect() }, 1000)
            }
            1000 -> {
                XLog.d(TAG, "WebSocket closed normally")
            }
            else -> {
                XLog.w(TAG, "Unknown close code: $code, attempting reconnect")
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
        XLog.d(TAG, "Reconnecting to: $url")
        connectWebSocket(url)
    }

    fun isConnected(): Boolean = isConnected

    fun getConnectionState(): String {
        return "connected=$isConnected, sessionId=$sessionId, lastSeq=$lastSeq, heartbeatInterval=$heartbeatInterval"
    }
}
