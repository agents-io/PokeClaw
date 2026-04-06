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
 * 微信通道处理器。
 * 严格对应官方 @tencent-weixin/openclaw-weixin@1.0.2 的:
 * - src/monitor/monitor.ts (monitorWeixinProvider 长轮询主循环)
 * - src/channel.ts (outbound sendText/sendMedia)
 * - src/messaging/send-media.ts (MIME 路由)
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

    /** 当前 bot 的 accountId（用于 contextToken 管理和 session guard） */
    private val accountId: String get() = botToken.substringBefore(":").ifEmpty { "default" }

    override fun isConnected(): Boolean = pollingActive

    override fun init() {
        if (botToken.isEmpty() || apiBaseUrl.isEmpty()) {
            XLog.w(TAG, "微信 Bot Token 或 API 地址未配置，微信通道将不可用")
            return
        }

        apiClient.setBotToken(botToken)
        apiClient.setBaseUrl(apiBaseUrl)

        // 从 MMKV 恢复 contextToken（对应 2.0.1 restoreContextTokens）
        WeChatInbound.restoreContextTokens(accountId)

        pollingActive = true
        pollingThread = Thread({
            runMonitorLoop()
        }, "wechat-monitor").apply { isDaemon = true; start() }

        XLog.i(TAG, "微信通道已启动")
    }

    /**
     * 长轮询主循环。
     * 严格对应 monitor.ts 的 monitorWeixinProvider()
     */
    private fun runMonitorLoop() {
        // 从 MMKV 恢复游标
        var getUpdatesBuf = KVUtils.getWechatUpdatesCursor()
        var nextTimeoutMs = DEFAULT_LONG_POLL_TIMEOUT_MS
        var consecutiveFailures = 0

        XLog.i(TAG, "monitor 启动: baseUrl=$apiBaseUrl, cursor=${if (getUpdatesBuf.isEmpty()) "(空)" else "...${getUpdatesBuf.takeLast(20)}"}")

        while (pollingActive) {
            try {
                // session guard 检查
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

                // 自适应超时（monitor.ts: 使用服务端返回的 longpolling_timeout_ms）
                resp.longpollingTimeoutMs?.let {
                    if (it > 0) nextTimeoutMs = it
                }

                // 检查 API 错误（monitor.ts: 同时检查 ret 和 errcode）
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
                    XLog.e(TAG, "getUpdates 错误: ret=${resp.ret}, errcode=${resp.errcode}, errmsg=${resp.errmsg} ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES)")
                    handleConsecutiveFailures(consecutiveFailures)
                    continue
                }

                consecutiveFailures = 0

                // 更新游标（无论是否有新消息都要更新，防止重复）
                if (!resp.getUpdatesBuf.isNullOrEmpty() && resp.getUpdatesBuf != getUpdatesBuf) {
                    getUpdatesBuf = resp.getUpdatesBuf
                    KVUtils.setWechatUpdatesCursor(getUpdatesBuf)
                }

                // 处理消息
                val msgs = resp.msgs ?: emptyList()
                for (msg in msgs) {
                    processInboundMessage(msg)
                }

            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (pollingActive) {
                    consecutiveFailures++
                    XLog.w(TAG, "monitor 异常 ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES)", e)
                    handleConsecutiveFailures(consecutiveFailures)
                }
            }
        }
        XLog.i(TAG, "monitor 已退出")
    }

    /** 处理连续失败退避（monitor.ts: 3 次失败 → 30s 退避） */
    private fun handleConsecutiveFailures(count: Int) {
        try {
            if (count >= MAX_CONSECUTIVE_FAILURES) {
                XLog.e(TAG, "$MAX_CONSECUTIVE_FAILURES 次连续失败，退避 ${BACKOFF_DELAY_MS / 1000}s")
                Thread.sleep(BACKOFF_DELAY_MS)
            } else {
                Thread.sleep(RETRY_DELAY_MS)
            }
        } catch (_: InterruptedException) {
            // exit
        }
    }

    /**
     * 处理收到的单条消息。
     * 对应 monitor.ts 中的消息处理部分 + inbound.ts 的 contextToken 管理。
     */
    private fun processInboundMessage(msg: WeChatMessage) {
        val fromUserId = msg.fromUserId
        if (fromUserId.isEmpty()) return

        // 缓存 contextToken（对应 inbound.ts setContextToken）
        if (!msg.contextToken.isNullOrEmpty()) {
            WeChatInbound.setContextToken(accountId, fromUserId, msg.contextToken)
        }

        // 打印完整的 item_list 详情（便于调试图片/语音/文件等消息结构）
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

        // 提取文本（支持纯文本、语音转文字、引用消息）
        val body = WeChatInbound.bodyFromItemList(msg.itemList)
        if (body.isEmpty()) {
            // 纯媒体消息（图片/视频/文件无附带文本），回复提示
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
                XLog.i(TAG, "收到纯媒体消息: types=$mediaTypes, from=${fromUserId.takeLast(16)}")
                val tip = app.getString(io.agents.pokeclaw.R.string.wechat_unsupported_media, mediaTypes.joinToString("+"))
                val contextToken = msg.contextToken ?: ""
                scope.launch {
                    WeChatSender.sendText(apiClient, fromUserId, tip, contextToken.ifEmpty { null })
                }
            } else {
                XLog.d(TAG, "消息无内容，跳过: from=${fromUserId.takeLast(16)}")
            }
            return
        }

        XLog.i(TAG, "[${channel.displayName}] 收到消息: ${body.take(80)}, from=${fromUserId.takeLast(16)}")
        lastFromUserId = fromUserId
        ChannelManager.dispatchMessage(channel, body, msg.contextToken ?: "")
    }

    // ==================== ChannelHandler 接口实现 ====================

    override fun flushMessages() {
        flushMessageBuffer()
    }

    override fun disconnect() {
        flushMessageBuffer()
        pollingActive = false
        pollingThread?.interrupt()
        pollingThread = null
        WeChatInbound.clearAll()
        XLog.i(TAG, "微信通道已停止")
    }

    override fun reinitFromStorage() {
        disconnect()
        botToken = KVUtils.getWechatBotToken()
        apiBaseUrl = KVUtils.getWechatApiBaseUrl()
        init()
    }

    // ==================== 消息合并缓冲（规避 iLink 频率限制） ====================
    // 策略：从第一条消息开始计时，BUFFER_DELAY_MS 后 flush。
    // 但至少攒够 MIN_BUFFER_COUNT 条才发，不够的话继续等下一个窗口。
    // 图片/文件不缓冲，但发送前会强制 flush 文本缓冲区。

    private val messageBuffer = mutableListOf<String>()
    private var bufferUserId: String? = null
    private var bufferContextToken: String? = null
    private var flushJob: kotlinx.coroutines.Job? = null

    /** 从第一条消息开始的缓冲窗口时间（毫秒） */
    private val BUFFER_DELAY_MS = 12000L
    /** 至少攒够这么多条才合并发送（不够的话继续等） */
    private val MIN_BUFFER_COUNT = 8

    /**
     * 强制 flush（不管条数），用于：图片/文件发送前、用户切换、disconnect。
     */
    private fun flushMessageBuffer() {
        doFlush(force = true)
    }

    /**
     * 定时器触发的 flush：检查是否达到最小条数。
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
            // 非强制模式下，不够 MIN_BUFFER_COUNT 条就不发，等下一个窗口
            if (!force && messageBuffer.size < MIN_BUFFER_COUNT) {
                // 重新启动一个定时器等下一个窗口
                flushJob?.cancel()
                flushJob = scope.launch {
                    kotlinx.coroutines.delay(BUFFER_DELAY_MS)
                    tryFlush()
                }
                XLog.d(TAG, "缓冲不足 $MIN_BUFFER_COUNT 条（当前 ${messageBuffer.size}），继续等待")
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
        // 每条消息单独做 markdown → plaintext，再合并（分隔符不经过 markdown 处理）
        val converted = messages.map { WeChatMarkdown.markdownToPlainText(it) }
        val merged = converted.joinToString("\n\n---\n\n")
        XLog.i(TAG, "合并发送 ${messages.size} 条消息 (${merged.length} 字符)")
        scope.launch {
            try {
                WeChatSender.sendRawText(apiClient, userId, merged, token)
            } catch (e: Exception) {
                XLog.e(TAG, "微信合并发送失败", e)
            }
        }
    }

    override fun sendMessage(content: String, messageID: String) {
        val fromUserId = resolveToUserId(messageID) ?: return
        val contextToken = resolveContextToken(fromUserId, messageID)
        synchronized(messageBuffer) {
            // 如果目标用户变了，先 flush 旧的
            if (bufferUserId != null && bufferUserId != fromUserId) {
                flushMessageBuffer()
            }
            messageBuffer.add(content)
            bufferUserId = fromUserId
            bufferContextToken = contextToken
            // 只在第一条消息时启动定时器（不重置）
            if (flushJob == null) {
                flushJob = scope.launch {
                    kotlinx.coroutines.delay(BUFFER_DELAY_MS)
                    tryFlush()
                }
            }
        }
    }

    override fun sendImage(imageBytes: ByteArray, messageID: String) {
        // 发图片前先 flush 文本缓冲区
        flushMessageBuffer()
        val fromUserId = resolveToUserId(messageID) ?: return
        val contextToken = resolveContextToken(fromUserId, messageID)
        scope.launch {
            try {
                WeChatSender.sendImage(apiClient, fromUserId, imageBytes, contextToken)
            } catch (e: Exception) {
                XLog.e(TAG, "微信发送图片失败", e)
            }
        }
    }

    override fun sendFile(file: File, messageID: String) {
        // 发文件前先 flush 文本缓冲区
        flushMessageBuffer()
        val fromUserId = resolveToUserId(messageID) ?: return
        val contextToken = resolveContextToken(fromUserId, messageID)
        scope.launch {
            try {
                WeChatSender.sendMediaFile(apiClient, fromUserId, file, contextToken)
            } catch (e: Exception) {
                XLog.e(TAG, "微信发送文件失败", e)
            }
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 从 contextToken store 中查找最近一个有 token 的用户。
     * messageID 在微信通道中就是 contextToken 值。
     */
    private fun resolveToUserId(messageID: String): String? {
        // messageID 在 dispatchMessage 时传入的是 msg.contextToken
        // 从 contextTokenStore 反查 userId
        if (messageID.isNotEmpty()) {
            val userId = WeChatInbound.findUserIdByContextToken(accountId, messageID)
            if (userId != null) return userId
        }

        // fallback: APP 重启后 contextTokenStore 为空，使用 lastFromUserId
        // （定时任务触发前通过 restoreRoutingContext 设置）
        val fallback = lastFromUserId
        if (fallback != null) {
            XLog.d(TAG, "resolveToUserId: contextToken 反查失败，使用 lastFromUserId")
            return fallback
        }

        XLog.w(TAG, "resolveToUserId: 无法找到目标用户")
        return null
    }

    private fun resolveContextToken(userId: String, messageID: String): String? {
        // 优先用 store 中最新的 token
        WeChatInbound.getContextToken(accountId, userId)?.let { return it }
        // messageID 本身可能是有效的 contextToken
        if (messageID.isNotEmpty()) return messageID
        // 重启后 store 为空，等待轮询获取新 contextToken（最多等 60 秒）
        XLog.i(TAG, "contextToken 不可用，等待轮询获取新 token (userId=${userId.takeLast(16)})")
        repeat(12) {
            try { Thread.sleep(5000) } catch (_: InterruptedException) { return null }
            WeChatInbound.getContextToken(accountId, userId)?.let { return it }
        }
        XLog.w(TAG, "等待 contextToken 超时")
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
            XLog.w(TAG, "微信 sendMessageToUser 失败：无法获取 contextToken，用户可能未发过消息: ${userId.takeLast(16)}")
            return
        }
        scope.launch {
            try {
                WeChatSender.sendText(apiClient, userId, content, contextToken)
            } catch (e: Exception) {
                XLog.e(TAG, "微信 sendMessageToUser 失败", e)
            }
        }
    }

    companion object {
        private const val TAG = "WeChatHandler"

        // monitor.ts 常量
        private const val DEFAULT_LONG_POLL_TIMEOUT_MS = 35_000L
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val BACKOFF_DELAY_MS = 30_000L
        private const val RETRY_DELAY_MS = 2_000L
    }
}
