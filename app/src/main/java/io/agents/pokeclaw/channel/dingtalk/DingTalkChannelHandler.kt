// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.dingtalk

import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.channel.ChannelHandler
import io.agents.pokeclaw.channel.ChannelManager
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import com.dingtalk.open.app.api.OpenDingTalkClient
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder
import com.dingtalk.open.app.api.callback.DingTalkStreamTopics
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener
import com.dingtalk.open.app.api.models.bot.ChatbotMessage
import com.dingtalk.open.app.api.security.AuthClientCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class DingTalkChannelHandler(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private var appKey: String,
    private var appSecret: String,
) : ChannelHandler {

    override val channel = Channel.DINGTALK

    private var client: OpenDingTalkClient? = null

    @Volatile
    private var lastWebhook: String? = null
    @Volatile
    private var lastSenderStaffId: String? = null
    @Volatile
    private var lastConversationType: String? = null
    @Volatile
    private var lastConversationId: String? = null
    @Volatile
    private var lastMsgId: String? = null

    @Volatile
    private var accessToken: String? = null
    @Volatile
    private var tokenExpireTime: Long = 0

    override fun isConnected(): Boolean = client != null

    override fun init() {
        if (appKey.isEmpty() || appSecret.isEmpty()) {
            XLog.w(TAG, "DingTalk AppKey/AppSecret not configured, DingTalk channel will be unavailable")
            return
        }

        client = OpenDingTalkStreamClientBuilder
            .custom()
            .credential(AuthClientCredential(appKey, appSecret))
            .registerCallbackListener(
                DingTalkStreamTopics.BOT_MESSAGE_TOPIC,
                object : OpenDingTalkCallbackListener<ChatbotMessage, Void> {
                    override fun execute(message: ChatbotMessage): Void? {
                        val text = message.text?.content ?: ""
                        XLog.i(TAG, "[${channel.displayName}] Message received: $text, sender: ${message.senderNick}, type: ${message.conversationType}")

                        lastWebhook = message.sessionWebhook
                        lastSenderStaffId = message.senderStaffId
                        lastConversationType = message.conversationType
                        lastConversationId = message.conversationId
                        lastMsgId = message.msgId
                        ChannelManager.dispatchMessage(channel, text, lastMsgId ?: "")
                        return null
                    }
                }
            )
            .build()

        scope.launch {
            try {
                client?.start()
                XLog.i(TAG, "DingTalk Stream client started")
            } catch (e: Exception) {
                XLog.e(TAG, "DingTalk Stream client failed to start", e)
            }
        }
    }

    override fun disconnect() {
        val oldClient = client ?: return
        client = null
        lastWebhook = null
        lastSenderStaffId = null
        lastConversationType = null
        lastConversationId = null
        accessToken = null
        tokenExpireTime = 0
        scope.launch {
            try {
                oldClient.stop()
                XLog.i(TAG, "DingTalk Stream client disconnected")
            } catch (e: Exception) {
                XLog.w(TAG, "Exception on DingTalk client disconnect", e)
            }
        }
    }

    override fun reinitFromStorage() {
        disconnect()
        appKey = KVUtils.getDingtalkAppKey()
        appSecret = KVUtils.getDingtalkAppSecret()
        init()
    }

    override fun sendMessage(content: String, messageID: String) {
        val webhook = lastWebhook
        if (webhook.isNullOrEmpty()) {
            XLog.w(TAG, "DingTalk reply failed: no available session webhook")
            return
        }
        scope.launch {
            try {
                val messageJson = com.google.gson.JsonObject().apply {
                    addProperty("msgtype", "markdown")
                    add("markdown", com.google.gson.JsonObject().apply {
                        addProperty("title", "PokeClaw Reply")
                        addProperty("text", content)
                    })
                }
                val body = okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(), messageJson.toString()
                )
                val request = okhttp3.Request.Builder()
                    .url(webhook)
                    .post(body)
                    .build()
                val response = httpClient.newCall(request).execute()
                XLog.i(TAG, "DingTalk reply response: ${response.code}")
                response.close()
            } catch (e: Exception) {
                XLog.e(TAG, "DingTalk reply failed", e)
            }
        }
    }

    override fun sendImage(imageBytes: ByteArray, messageID: String) {
        scope.launch {
            try {
                val mediaId = uploadImage(imageBytes)
                if (mediaId == null) {
                    XLog.e(TAG, "DingTalk image send failed: upload did not return mediaId")
                    return@launch
                }
                val token = getAccessToken()
                if (token == null) {
                    XLog.e(TAG, "DingTalk image send failed: could not get accessToken")
                    return@launch
                }

                val msgParam = com.google.gson.JsonObject().apply {
                    addProperty("photoURL", mediaId)
                }.toString()

                sendRobotMessage(token, "sampleImageMsg", msgParam)
            } catch (e: Exception) {
                XLog.e(TAG, "DingTalk image send failed", e)
            }
        }
    }

    override fun sendFile(file: java.io.File, messageID: String) {
        val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
        val isImage = file.extension.lowercase() in imageExtensions

        scope.launch {
            try {
                val msgKey: String
                val msgParam: String

                if (isImage) {
                    val mediaId = uploadImage(file.readBytes())
                    if (mediaId == null) {
                        XLog.e(TAG, "DingTalk file send failed: image upload did not return mediaId")
                        return@launch
                    }
                    msgKey = "sampleImageMsg"
                    msgParam = com.google.gson.JsonObject().apply {
                        addProperty("photoURL", mediaId)
                    }.toString()
                } else {
                    val mediaId = uploadFile(file)
                    if (mediaId == null) {
                        XLog.e(TAG, "DingTalk file send failed: file upload did not return mediaId")
                        return@launch
                    }
                    msgKey = "sampleFile"
                    msgParam = com.google.gson.JsonObject().apply {
                        addProperty("mediaId", mediaId)
                        addProperty("fileName", file.name)
                        addProperty("fileType", file.extension.ifEmpty { "file" })
                    }.toString()
                }

                val token = getAccessToken()
                if (token == null) {
                    XLog.e(TAG, "DingTalk file send failed: could not get accessToken")
                    return@launch
                }

                sendRobotMessage(token, msgKey, msgParam)
            } catch (e: Exception) {
                XLog.e(TAG, "DingTalk file send failed", e)
            }
        }
    }

    // ---------- Internal helper methods ----------

    private fun getAccessToken(): String? {
        val cached = accessToken
        if (cached != null && System.currentTimeMillis() < tokenExpireTime) return cached
        if (appKey.isEmpty() || appSecret.isEmpty()) return null

        return try {
            val json = com.google.gson.JsonObject().apply {
                addProperty("appKey", appKey)
                addProperty("appSecret", appSecret)
            }
            val request = okhttp3.Request.Builder()
                .url("https://api.dingtalk.com/v1.0/oauth2/accessToken")
                .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), json.toString()))
                .build()
            val response = httpClient.newCall(request).execute()
            val respBody = response.body?.string()
            response.close()

            val respJson = com.google.gson.JsonParser.parseString(respBody).asJsonObject
            val token = respJson.get("accessToken")?.asString
            val expireIn = respJson.get("expireIn")?.asLong ?: 7200

            if (token != null) {
                accessToken = token
                tokenExpireTime = System.currentTimeMillis() + (expireIn - 300) * 1000
                XLog.i(TAG, "DingTalk access_token obtained, expireIn=${expireIn}s")
            }
            token
        } catch (e: Exception) {
            XLog.e(TAG, "DingTalk access_token fetch failed", e)
            null
        }
    }

    private fun uploadImage(imageBytes: ByteArray): String? {
        val token = getAccessToken() ?: return null
        return try {
            val mediaBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "media", "screenshot.png",
                    imageBytes.toRequestBody("image/png".toMediaTypeOrNull())
                )
                .build()
            val request = okhttp3.Request.Builder()
                .url("https://oapi.dingtalk.com/media/upload?access_token=$token&type=image")
                .post(mediaBody)
                .build()
            val response = httpClient.newCall(request).execute()
            val respBody = response.body?.string()
            response.close()

            val respJson = com.google.gson.JsonParser.parseString(respBody).asJsonObject
            val mediaId = respJson.get("media_id")?.asString
            XLog.i(TAG, "DingTalk image uploaded: mediaId=$mediaId")
            mediaId
        } catch (e: Exception) {
            XLog.e(TAG, "DingTalk image upload failed", e)
            null
        }
    }

    private fun uploadFile(file: java.io.File): String? {
        val token = getAccessToken() ?: return null
        return try {
            val mimeType = when (file.extension.lowercase()) {
                "pdf" -> "application/pdf"
                "doc" -> "application/msword"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "xls" -> "application/vnd.ms-excel"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                "ppt" -> "application/vnd.ms-powerpoint"
                "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                "zip" -> "application/zip"
                "txt", "log", "csv", "json", "xml", "yaml", "yml" -> "text/plain"
                else -> "application/octet-stream"
            }
            val mediaBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "media", file.name,
                    file.readBytes().toRequestBody(mimeType.toMediaTypeOrNull())
                )
                .build()
            val request = okhttp3.Request.Builder()
                .url("https://oapi.dingtalk.com/media/upload?access_token=$token&type=file")
                .post(mediaBody)
                .build()
            val response = httpClient.newCall(request).execute()
            val respBody = response.body?.string()
            response.close()

            val respJson = com.google.gson.JsonParser.parseString(respBody).asJsonObject
            val mediaId = respJson.get("media_id")?.asString
            XLog.i(TAG, "DingTalk file uploaded: mediaId=$mediaId")
            mediaId
        } catch (e: Exception) {
            XLog.e(TAG, "DingTalk file upload failed", e)
            null
        }
    }

    /**
     * Send a message via the DingTalk bot API. oToMessages/batchSend for direct chat, groupMessages/send for group chat.
     */
    private fun sendRobotMessage(token: String, msgKey: String, msgParam: String) {
        val convType = lastConversationType

        val (url, bodyJson) = if (convType == "1") {
            val staffId = lastSenderStaffId
            if (staffId.isNullOrEmpty()) {
                XLog.e(TAG, "DingTalk message send failed: senderStaffId is empty")
                return
            }
            "https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend" to
                com.google.gson.JsonObject().apply {
                    addProperty("robotCode", appKey)
                    add("userIds", com.google.gson.JsonArray().apply { add(staffId) })
                    addProperty("msgKey", msgKey)
                    addProperty("msgParam", msgParam)
                }.toString()
        } else {
            val convId = lastConversationId
            if (convId.isNullOrEmpty()) {
                XLog.e(TAG, "DingTalk message send failed: conversationId is empty")
                return
            }
            "https://api.dingtalk.com/v1.0/robot/groupMessages/send" to
                com.google.gson.JsonObject().apply {
                    addProperty("robotCode", appKey)
                    addProperty("openConversationId", convId)
                    addProperty("msgKey", msgKey)
                    addProperty("msgParam", msgParam)
                }.toString()
        }

        val request = okhttp3.Request.Builder()
            .url(url)
            .addHeader("x-acs-dingtalk-access-token", token)
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), bodyJson))
            .build()
        val response = httpClient.newCall(request).execute()
        val respBody = response.body?.string()
        XLog.i(TAG, "DingTalk message send response: code=${response.code}, body=$respBody")
        response.close()
    }

    override fun getLastSenderId(): String? {
        val convType = lastConversationType ?: return null
        return if (convType == "1") {
            lastSenderStaffId?.let { "1:$it" }
        } else {
            lastConversationId?.let { "group:$it" }
        }
    }

    override fun restoreRoutingContext(targetUserId: String) {
        val parts = targetUserId.split(":", limit = 2)
        if (parts.size == 2) {
            if (parts[0] == "1") {
                lastConversationType = "1"
                lastSenderStaffId = parts[1]
            } else {
                lastConversationType = "2"
                lastConversationId = parts[1]
            }
        }
    }

    override fun sendMessageToUser(userId: String, content: String) {
        if (userId.isEmpty() || content.isBlank()) return
        val parts = userId.split(":", limit = 2)
        if (parts.size != 2) {
            XLog.w(TAG, "DingTalk sendMessageToUser failed: invalid userId format: $userId")
            return
        }
        scope.launch {
            try {
                val token = getAccessToken()
                if (token == null) {
                    XLog.e(TAG, "DingTalk sendMessageToUser failed: could not get accessToken")
                    return@launch
                }
                val msgParam = com.google.gson.JsonObject().apply {
                    addProperty("content", content)
                }.toString()

                val (url, bodyJson) = if (parts[0] == "1") {
                    "https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend" to
                        com.google.gson.JsonObject().apply {
                            addProperty("robotCode", appKey)
                            add("userIds", com.google.gson.JsonArray().apply { add(parts[1]) })
                            addProperty("msgKey", "sampleText")
                            addProperty("msgParam", msgParam)
                        }.toString()
                } else {
                    "https://api.dingtalk.com/v1.0/robot/groupMessages/send" to
                        com.google.gson.JsonObject().apply {
                            addProperty("robotCode", appKey)
                            addProperty("openConversationId", parts[1])
                            addProperty("msgKey", "sampleText")
                            addProperty("msgParam", msgParam)
                        }.toString()
                }

                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("x-acs-dingtalk-access-token", token)
                    .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), bodyJson))
                    .build()
                val response = httpClient.newCall(request).execute()
                XLog.i(TAG, "DingTalk sendMessageToUser response: ${response.code}")
                response.close()
            } catch (e: Exception) {
                XLog.e(TAG, "DingTalk sendMessageToUser failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "DingTalkHandler"
    }
}
