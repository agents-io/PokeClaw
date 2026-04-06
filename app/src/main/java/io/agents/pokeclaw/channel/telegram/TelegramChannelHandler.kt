// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.telegram

import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.channel.ChannelHandler
import io.agents.pokeclaw.channel.ChannelManager
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TelegramChannelHandler(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private var botToken: String,
) : ChannelHandler {

    override val channel = Channel.TELEGRAM

    @Volatile
    private var lastChatId: Long? = null
    @Volatile
    private var pollingActive = false
    private var pollingThread: Thread? = null

    private val pollingHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun apiUrl(method: String): String =
        "https://api.telegram.org/bot$botToken/$method"

    override fun isConnected(): Boolean = pollingActive

    override fun init() {
        if (botToken.isEmpty()) {
            XLog.w(TAG, "Telegram Bot Token 未配置，Telegram 通道将不可用")
            return
        }

        pollingActive = true
        pollingThread = Thread({
            var offset = 0L
            XLog.i(TAG, "Telegram polling 线程启动")
            while (pollingActive) {
                try {
                    val reqBody = JSONObject().apply {
                        put("offset", offset)
                        put("timeout", 30)
                        put("allowed_updates", org.json.JSONArray().put("message"))
                    }
                    val request = okhttp3.Request.Builder()
                        .url(apiUrl("getUpdates"))
                        .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), reqBody.toString()))
                        .build()
                    val response = pollingHttpClient.newCall(request).execute()
                    val body = response.body?.string()
                    val code = response.code
                    response.close()

                    if (body == null) {
                        XLog.w(TAG, "Telegram getUpdates 响应 body 为空, code=$code")
                        continue
                    }

                    val json = JSONObject(body)
                    if (!json.optBoolean("ok", false)) {
                        XLog.e(TAG, "Telegram getUpdates 失败: code=$code, body=$body")
                        if (code == 401 || code == 404) {
                            XLog.e(TAG, "Telegram Bot Token 无效，停止轮询")
                            break
                        }
                        Thread.sleep(5000)
                        continue
                    }

                    val result = json.optJSONArray("result") ?: continue
                    for (i in 0 until result.length()) {
                        val update = result.getJSONObject(i)
                        val updateId = update.getLong("update_id")
                        if (updateId >= offset) offset = updateId + 1

                        val message = update.optJSONObject("message") ?: continue
                        val text = message.optString("text", "")
                        if (text.isEmpty()) continue

                        val chatId = message.getJSONObject("chat").getLong("id")
                        val messageId = message.getInt("message_id")
                        lastChatId = chatId

                        XLog.i(TAG, "[${channel.displayName}] 收到消息: $text, chatId=$chatId")
                        ChannelManager.dispatchMessage(channel, text, messageId.toString())
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    XLog.d(TAG, "Telegram polling 超时，继续轮询")
                } catch (e: Exception) {
                    if (pollingActive) {
                        XLog.w(TAG, "Telegram polling 异常，5 秒后重试", e)
                        try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                    }
                }
            }
            XLog.i(TAG, "Telegram polling 线程已退出")
        }, "telegram-polling").apply { isDaemon = true; start() }

        XLog.i(TAG, "Telegram Long Polling 已启动")
    }

    override fun disconnect() {
        pollingActive = false
        pollingThread?.interrupt()
        pollingThread = null
        lastChatId = null
        XLog.i(TAG, "Telegram Long Polling 已停止")
    }

    override fun reinitFromStorage() {
        disconnect()
        botToken = KVUtils.getTelegramBotToken()
        init()
    }

    override fun sendMessage(content: String, messageID: String) {
        val chatId = lastChatId
        if (chatId == null) {
            XLog.w(TAG, "Telegram 回复失败：没有可用的 chatId")
            return
        }
        scope.launch {
            try {
                if (TelegramMarkdownUtils.containsMarkdown(content)) {
                    val v2 = TelegramMarkdownUtils.markdownToTelegramV2(content)
                    val ok = sendText(chatId, v2, "MarkdownV2")
                    if (!ok) sendText(chatId, content, null)
                } else {
                    sendText(chatId, TelegramMarkdownUtils.escapePlain(content), "MarkdownV2")
                }
            } catch (e: Exception) {
                XLog.e(TAG, "Telegram 回复失败", e)
            }
        }
    }

    override fun sendImage(imageBytes: ByteArray, messageID: String) {
        val chatId = lastChatId ?: return
        scope.launch {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId.toString())
                    .addFormDataPart(
                        "photo", "screenshot.png",
                        imageBytes.toRequestBody("image/png".toMediaTypeOrNull())
                    )
                    .build()
                val request = okhttp3.Request.Builder()
                    .url(apiUrl("sendPhoto"))
                    .post(body)
                    .build()
                val response = httpClient.newCall(request).execute()
                XLog.i(TAG, "Telegram 图片发送响应: ${response.code}")
                response.close()
            } catch (e: Exception) {
                XLog.e(TAG, "Telegram 发送图片失败", e)
            }
        }
    }

    override fun sendFile(file: java.io.File, messageID: String) {
        val chatId = lastChatId ?: return
        scope.launch {
            try {
                val mimeType = when (file.extension.lowercase()) {
                    "png", "jpg", "jpeg", "gif", "bmp", "webp" -> "image/${file.extension.lowercase()}"
                    else -> "application/octet-stream"
                }
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId.toString())
                    .addFormDataPart(
                        "document", file.name,
                        file.readBytes().toRequestBody(mimeType.toMediaTypeOrNull())
                    )
                    .build()
                val request = okhttp3.Request.Builder()
                    .url(apiUrl("sendDocument"))
                    .post(body)
                    .build()
                val response = httpClient.newCall(request).execute()
                XLog.i(TAG, "Telegram 文件发送响应: ${response.code}")
                response.close()
            } catch (e: Exception) {
                XLog.e(TAG, "Telegram 发送文件失败", e)
            }
        }
    }

    override fun getLastSenderId(): String? = lastChatId?.toString()

    override fun restoreRoutingContext(targetUserId: String) {
        targetUserId.toLongOrNull()?.let { lastChatId = it }
    }

    override fun sendMessageToUser(userId: String, content: String) {
        val chatId = userId.toLongOrNull()
        if (chatId == null) {
            XLog.w(TAG, "Telegram sendMessageToUser 失败：无效的 chatId: $userId")
            return
        }
        scope.launch {
            try {
                if (TelegramMarkdownUtils.containsMarkdown(content)) {
                    val v2 = TelegramMarkdownUtils.markdownToTelegramV2(content)
                    val ok = sendText(chatId, v2, "MarkdownV2")
                    if (!ok) sendText(chatId, content, null)
                } else {
                    sendText(chatId, TelegramMarkdownUtils.escapePlain(content), "MarkdownV2")
                }
            } catch (e: Exception) {
                XLog.e(TAG, "Telegram sendMessageToUser 失败", e)
            }
        }
    }

    // ---------- 内部工具方法 ----------

    private fun sendText(chatId: Long, text: String, parseMode: String?): Boolean {
        val json = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
            if (parseMode != null) put("parse_mode", parseMode)
        }
        val body = okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), json.toString())
        val request = okhttp3.Request.Builder()
            .url(apiUrl("sendMessage"))
            .post(body)
            .build()
        val response = httpClient.newCall(request).execute()
        val code = response.code
        if (code !in 200..299) {
            val respBody = response.body?.string()
            XLog.w(TAG, "Telegram sendMessage 失败: code=$code, parseMode=$parseMode, resp=$respBody")
        } else {
            XLog.i(TAG, "Telegram 回复成功: code=$code, parseMode=$parseMode")
        }
        response.close()
        return code in 200..299
    }

    companion object {
        private const val TAG = "TelegramHandler"
    }
}
