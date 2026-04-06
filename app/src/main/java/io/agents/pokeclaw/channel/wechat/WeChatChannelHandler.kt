// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.wechat

import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.channel.ChannelHandler
import io.agents.pokeclaw.channel.ChannelManager
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * WeChat channel handler.
 * Strictly mirrors the official @tencent-weixin/openclaw-weixin@1.0.2:
 * - src/monitor/monitor.ts (monitorWeixinProvider long-polling main loop)
 * - src/channel.ts (outbound sendText/sendMedia)
 * - src/messaging/send-media.ts (MIME routing)
 */
class WeChatChannelHandler(
    private val scope: CoroutineScope,
    private var botToken: String,
    private var apiBaseUrl: String,
) : ChannelHandler {

    override val channel = Channel.WECHAT

    @Volatile
    private var pollingActive = false
    private var pollingThread: Thread? = null
    @Volatile
    private var lastFromUserId: String? = null

    private val apiClient = WeChatApiClient()

    /** Current bot accountId (used for contextToken management and session guard) */
    private val accountId: String get() = botToken.substringBefore(":").ifEmpty { "default" }

    override fun isConnected(): Boolean = pollingActive

    override fun init() {
        if (botToken.isEmpty() || apiBaseUrl.isEmpty()) {
            XLog.w(TAG, "WeChat Bot Token or API address not configured, WeChat channel will be unavailable")
            return
        }

        apiClient.setBotToken(botToken)
        apiClient.setBaseUrl(apiBaseUrl)

        // Restore contextToken from MMKV (corresponds to 2.0.1 restoreContextTokens)
        WeChatInbound.restoreContextTokens(accountId)

        pollingActive = true
        pollingThread = Thread({
            runMonitorLoop()
        }, "wechat-monitor").apply { isDaemon = true; start() }

                XLog.i(TAG, "WeChat channel started")
    }

    /**
     * Long-polling main loop.
     * Strictly mirrors monitorWeixinProvider() in monitor.ts
     */
    private fun runMonitorLoop() {
        // Restore cursor from MMKV
        var getUpdatesBuf = KVUtils.getWechatUpdatesCursor()
        var nextTimeoutMs = DEFAULT_LONG_POLL_TIMEOUT_MS
        var consecutiveFailures = 0

        XLog.i(TAG, "monitor started: baseUrl=$apiBaseUrl, cursor=${if (getUpdatesBuf.isEmpty()) "(empty)" else "...${getUpdatesBuf.takeLast(20)}"}")

        while (pollingActive) {
            try {
                // session guard check
                if (WeChatApiClient.isSessionPaused(accountId)) {
                    val remainMs = WeChatApiClient.getRemainingPauseMs(accountId)
                    XLog.w(TAG, "session paused, sleeping ${remainMs / 1000}s")
                    Thread.sleep(remainMs.coerceAtMost(30_000))
                    continue
                }

                val resp = apiClient.getUpdates(getUpdatesBuf)
                if (resp == null) {
                    consecutiveFailures++
                    handleConsecutiveFailures(consecutiveFailures)
                    continue
                }

                // Adaptive timeout (monitor.ts: uses server-returned longpolling_timeout_ms)
                resp.longpollingTimeoutMs?.let {
                    if (it > 0) nextTimeoutMs = it
                }

                // Check API errors (monitor.ts: checks both ret and errcode)
                val isApiError = (resp.ret != null && resp.ret != 0) ||
                        (resp.errcode != null && resp.errcode != 0)
                if (isApiError) {
                    val isSessionExpired = resp.errcode == SESSION_EXPIRED_ERRCODE ||
                            resp.ret == SESSION_EXPIRED_ERRCODE
                    if (isSessionExpired) {
                        WeChatApiClient.pauseSession(accountId)
                        val pauseMs = WeChatApiClient.getRemainingPauseMs(accountId)
                        XLog.e(TAG, "session expired (errcode=$SESSION_EXPIRED_ERRCODE), pausing ${pauseMs / 60_000} min")
                        consecutiveFailures = 0
                        Thread.sleep(pauseMs.coerceAtMost(30_000))
                        continue
                    }

                    consecutiveFailures++
                    XLog.e(TAG, "getUpdates error: ret=${resp.ret}, errcode=${resp.errcode}, errmsg=${resp.errmsg} ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES)")
                    handleConsecutiveFailures(consecutiveFailures)
                    continue
                }

                consecutiveFailures = 0

                // Update cursor (update regardless of whether there are new messages, to avoid duplicates)
                if (!resp.getUpdatesBuf.isNullOrEmpty() && resp.getUpdatesBuf != getUpdatesBuf) {
                    getUpdatesBuf = resp.getUpdatesBuf
                    KVUtils.setWechatUpdatesCursor(getUpdatesBuf)
                }

                // Process messages
                val msgs = resp.msgs ?: emptyList()
                for (msg in msgs) {
                    processInboundMessage(msg)
                }

            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (pollingActive) {
                    consecutiveFailures++
                    XLog.w(TAG, "monitor exception ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES)", e)
                    handleConsecutiveFailures(consecutiveFailures)
                }
            }
        }
        XLog.i(TAG, "monitor exited")
    }

    /** Handle consecutive failure backoff (monitor.ts: 3 failures → 30s backoff) */
    private fun handleConsecutiveFailures(count: Int) {
        try {
            if (count >= MAX_CONSECUTIVE_FAILURES) {
                XLog.e(TAG, "$MAX_CONSECUTIVE_FAILURES consecutive failures, backing off ${BACKOFF_DELAY_MS / 1000}s")
                Thread.sleep(BACKOFF_DELAY_MS)
            } else {
                Thread.sleep(RETRY_DELAY_MS)
            }
        } catch (_: InterruptedException) {
            // exit
        }
    }

    /**
     * Handle a single received message.
     * Corresponds to the message handling section in monitor.ts + contextToken management in inbound.ts.
     */
    private fun processInboundMessage(msg: WeChatMessage) {
        val fromUserId = msg.fromUserId
        if (fromUserId.isEmpty()) return

        // Cache contextToken (corresponds to inbound.ts setContextToken)
        if (!msg.contextToken.isNullOrEmpty()) {
            WeChatInbound.setContextToken(accountId, fromUserId, msg.contextToken)
        }

        // Log full item_list details (useful for debugging image/voice/file message structures)
        msg.itemList?.forEachIndexed { index, item ->
            val typeStr = when (item.type) {
                MessageItemType.TEXT -> "TEXT"
                MessageItemType.IMAGE -> "IMAGE"
                MessageItemType.VOICE -> "VOICE"
                MessageItemType.FILE -> "FILE"
                MessageItemType.VIDEO -> "VIDEO"
                else -> "UNKNOWN(${item.type})"
            }
            XLog.i(TAG, "  item[$index] type=$typeStr")
            item.textItem?.let { XLog.i(TAG, "    text_item: text=${it.text?.take(80)}") }
            item.imageItem?.let { img ->
                XLog.i(TAG, "    image_item:")
                XLog.i(TAG, "      media.encrypt_query_param=${img.media?.encryptQueryParam?.take(60)}...")
                XLog.i(TAG, "      media.aes_key=${img.media?.aesKey?.take(30)}...")
                XLog.i(TAG, "      media.encrypt_type=${img.media?.encryptType}")
                XLog.i(TAG, "      aeskey(hex)=${img.aeskey?.take(30)}")
                XLog.i(TAG, "      mid_size=${img.midSize}, hd_size=${img.hdSize}, thumb_size=${img.thumbSize}")
                XLog.i(TAG, "      thumb_media.encrypt_query_param=${img.thumbMedia?.encryptQueryParam?.take(40)}...")
                XLog.i(TAG, "      url=${img.url}")
            }
            item.voiceItem?.let { v ->
                XLog.i(TAG, "    voice_item: text=${v.text}, playtime=${v.playtime}, encode_type=${v.encodeType}")
                XLog.i(TAG, "      media.encrypt_query_param=${v.media?.encryptQueryParam?.take(40)}...")
            }
            item.fileItem?.let { f ->
                XLog.i(TAG, "    file_item: file_name=${f.fileName}, len=${f.len}")
                XLog.i(TAG, "      media.encrypt_query_param=${f.media?.encryptQueryParam?.take(40)}...")
            }
            item.videoItem?.let { v ->
                XLog.i(TAG, "    video_item: video_size=${v.videoSize}")
                XLog.i(TAG, "      media.encrypt_query_param=${v.media?.encryptQueryParam?.take(40)}...")
            }
            item.refMsg?.let { ref ->
                XLog.i(TAG, "    ref_msg: title=${ref.title}, item_type=${ref.messageItem?.type}")
            }
        }

        // Extract text (supports plain text, voice-to-text, and quoted messages)
        val body = WeChatInbound.bodyFromItemList(msg.itemList)
        if (body.isEmpty()) {
            // Pure media message (image/video/file with no accompanying text), reply with prompt
            val app = io.agents.pokeclaw.ClawApplication.instance
            val mediaTypes = msg.itemList?.filter { WeChatInbound.isMediaItem(it) }?.map {
                when (it.type) {
                    MessageItemType.IMAGE -> app.getString(io.agents.pokeclaw.R.string.wechat_media_image)
                    MessageItemType.VIDEO -> app.getString(io.agents.pokeclaw.R.string.wechat_media_video)
                    MessageItemType.FILE -> app.getString(io.agents.pokeclaw.R.string.wechat_media_file)
                    MessageItemType.VOICE -> app.getString(io.agents.pokeclaw.R.string.wechat_media_voice)
                    else -> app.getString(io.agents.pokeclaw.R.string.wechat_media_unknown)
                }
            } ?: emptyList()
            if (mediaTypes.isNotEmpty()) {
                XLog.i(TAG, "Pure media message received: types=$mediaTypes, from=${fromUserId.takeLast(16)}")
                val tip = app.getString(io.agents.pokeclaw.R.string.wechat_unsupported_media, mediaTypes.joinToString("+"))
                val contextToken = msg.contextToken ?: ""
                scope.launch {
                    WeChatSender.sendText(apiClient, fromUserId, tip, contextToken.ifEmpty { null })
                }
            } else {
                XLog.d(TAG, "Message has no content, skipping: from=${fromUserId.takeLast(16)}")
            }
            return
        }

        XLog.i(TAG, "[${channel.displayName}] Message received: ${body.take(80)}, from=${fromUserId.takeLast(16)}")
        lastFromUserId = fromUserId
        ChannelManager.dispatchMessage(channel, body, msg.contextToken ?: "")
    }

    // ==================== ChannelHandler Interface Implementation ====================

    override fun flushMessages() {
        flushMessageBuffer()
    }

    override fun disconnect() {
        flushMessageBuffer()
        pollingActive = false
        pollingThread?.interrupt()
        pollingThread = null
        WeChatInbound.clearAll()
        XLog.i(TAG, "WeChat channel stopped")
    }

    override fun reinitFromStorage() {
        disconnect()
        botToken = KVUtils.getWechatBotToken()
        apiBaseUrl = KVUtils.getWechatApiBaseUrl()
        init()
    }

    // ==================== Message Merge Buffer (to avoid iLink rate limits) ====================
    // Strategy: start timer from the first message, flush after BUFFER_DELAY_MS.
    // But only send if at least MIN_BUFFER_COUNT messages have accumulated; otherwise wait for the next window.
    // Images/files are not buffered, but flush the text buffer before sending.

    private val messageBuffer = mutableListOf<String>()
    private var bufferUserId: String? = null
    private var bufferContextToken: String? = null
    private var flushJob: kotlinx.coroutines.Job? = null

    /** Buffer window duration from the first message (milliseconds) */
    private val BUFFER_DELAY_MS = 12000L
    /** Minimum number of messages to accumulate before merging and sending (wait if below this) */
    private val MIN_BUFFER_COUNT = 8

    /**
     * Force flush (regardless of count), used for: before image/file send, user switch, disconnect.
     */
    private fun flushMessageBuffer() {
        doFlush(force = true)
    }

    /**
     * Timer-triggered flush: checks whether the minimum message count has been reached.
     */
    private fun tryFlush() {
        doFlush(force = false)
    }

    private fun doFlush(force: Boolean) {
        val messages: List<String>
        val userId: String
        val token: String?
        synchronized(messageBuffer) {
            if (messageBuffer.isEmpty()) return
            // In non-force mode, do not send if below MIN_BUFFER_COUNT; wait for the next window
            if (!force && messageBuffer.size < MIN_BUFFER_COUNT) {
                // Restart a timer to wait for the next window
                flushJob?.cancel()
                flushJob = scope.launch {
                    kotlinx.coroutines.delay(BUFFER_DELAY_MS)
                    tryFlush()
                }
                XLog.d(TAG, "Buffer below $MIN_BUFFER_COUNT messages (current ${messageBuffer.size}), continuing to wait")
                return
            }
            messages = messageBuffer.toList()
            userId = bufferUserId ?: return
            token = bufferContextToken
            messageBuffer.clear()
            bufferUserId = null
            bufferContextToken = null
            flushJob?.cancel()
            flushJob = null
        }
        // Each message is individually converted markdown → plaintext, then merged (separator is not markdown-processed)
        val converted = messages.map { WeChatMarkdown.markdownToPlainText(it) }
        val merged = converted.joinToString("\n\n---\n\n")
        XLog.i(TAG, "Merged send of ${messages.size} messages (${merged.length} chars)")
        scope.launch {
            try {
                WeChatSender.sendRawText(apiClient, userId, merged, token)
            } catch (e: Exception) {
                XLog.e(TAG, "WeChat merged send failed", e)
            }
        }
    }

    override fun sendMessage(content: String, messageID: String) {
        val fromUserId = resolveToUserId(messageID) ?: return
        val contextToken = resolveContextToken(fromUserId, messageID)
        synchronized(messageBuffer) {
            // If the target user changed, flush the old buffer first
            if (bufferUserId != null && bufferUserId != fromUserId) {
                flushMessageBuffer()
            }
            messageBuffer.add(content)
            bufferUserId = fromUserId
            bufferContextToken = contextToken
            // Only start the timer on the first message (do not reset)
            if (flushJob == null) {
                flushJob = scope.launch {
                    kotlinx.coroutines.delay(BUFFER_DELAY_MS)
                    tryFlush()
                }
            }
        }
    }

    override fun sendImage(imageBytes: ByteArray, messageID: String) {
        // Flush text buffer before sending image
        flushMessageBuffer()
        val fromUserId = resolveToUserId(messageID) ?: return
        val contextToken = resolveContextToken(fromUserId, messageID)
        scope.launch {
            try {
                WeChatSender.sendImage(apiClient, fromUserId, imageBytes, contextToken)
            } catch (e: Exception) {
                XLog.e(TAG, "WeChat image send failed", e)
            }
        }
    }

    override fun sendFile(file: File, messageID: String) {
        // Flush text buffer before sending file
        flushMessageBuffer()
        val fromUserId = resolveToUserId(messageID) ?: return
        val contextToken = resolveContextToken(fromUserId, messageID)
        scope.launch {
            try {
                WeChatSender.sendMediaFile(apiClient, fromUserId, file, contextToken)
            } catch (e: Exception) {
                XLog.e(TAG, "WeChat file send failed", e)
            }
        }
    }

    // ==================== Internal Utilities ====================

    /**
     * Find the most recent user with a token in the contextToken store.
     * messageID in the WeChat channel is the contextToken value.
     */
    private fun resolveToUserId(messageID: String): String? {
        // messageID passed in dispatchMessage is msg.contextToken
        // Reverse-lookup userId from contextTokenStore
        if (messageID.isNotEmpty()) {
            val userId = WeChatInbound.findUserIdByContextToken(accountId, messageID)
            if (userId != null) return userId
        }

        // Fallback: after app restart contextTokenStore is empty, use lastFromUserId
        // (set via restoreRoutingContext before scheduled task triggers)
        val fallback = lastFromUserId
        if (fallback != null) {
            XLog.d(TAG, "resolveToUserId: contextToken reverse-lookup failed, using lastFromUserId")
            return fallback
        }

        XLog.w(TAG, "resolveToUserId: cannot find target user")
        return null
    }

    private fun resolveContextToken(userId: String, messageID: String): String? {
        // Prefer the latest token in the store
        WeChatInbound.getContextToken(accountId, userId)?.let { return it }
        // messageID itself may be a valid contextToken
        if (messageID.isNotEmpty()) return messageID
        // After restart store is empty, wait for polling to get a new contextToken (up to 60 seconds)
        XLog.i(TAG, "contextToken unavailable, waiting for polling to get new token (userId=${userId.takeLast(16)})")
        repeat(12) {
            try { Thread.sleep(5000) } catch (_: InterruptedException) { return null }
            WeChatInbound.getContextToken(accountId, userId)?.let { return it }
        }
        XLog.w(TAG, "Timed out waiting for contextToken")
        return null
    }

    override fun getLastSenderId(): String? = lastFromUserId

    override fun restoreRoutingContext(targetUserId: String) {
        if (targetUserId.isNotEmpty()) lastFromUserId = targetUserId
    }

    override fun sendMessageToUser(userId: String, content: String) {
        if (userId.isEmpty() || content.isBlank()) return
        val contextToken = WeChatInbound.getContextToken(accountId, userId)
        if (contextToken == null) {
            XLog.w(TAG, "WeChat sendMessageToUser failed: could not get contextToken, user may not have sent a message yet: ${userId.takeLast(16)}")
            return
        }
        scope.launch {
            try {
                WeChatSender.sendText(apiClient, userId, content, contextToken)
            } catch (e: Exception) {
                XLog.e(TAG, "WeChat sendMessageToUser failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "WeChatHandler"

        // monitor.ts constants
        private const val DEFAULT_LONG_POLL_TIMEOUT_MS = 35_000L
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val BACKOFF_DELAY_MS = 30_000L
        private const val RETRY_DELAY_MS = 2_000L
    }
}
