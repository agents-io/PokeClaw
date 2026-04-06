// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.feishu

import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.channel.ChannelHandler
import io.agents.pokeclaw.channel.ChannelManager
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import com.lark.oapi.core.utils.Jsons
import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.CreateImageReq
import com.lark.oapi.service.im.v1.model.CreateImageReqBody
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import com.lark.oapi.service.im.v1.model.ReplyMessageReq
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody
import com.lark.oapi.ws.Client as FeishuWsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class FeiShuChannelHandler(
    private val scope: CoroutineScope,
    private var appId: String,
    private var appSecret: String,
) : ChannelHandler {

    override val channel = Channel.FEISHU

    private var apiClient: com.lark.oapi.Client? = null
    private var wsClient: FeishuWsClient? = null

    @Volatile
    private var lastMessageId: String? = null

    private val eventHandler: EventDispatcher by lazy {
        EventDispatcher.newBuilder("", "")
            .onP2MessageReceiveV1(object : ImService.P2MessageReceiveV1Handler() {
                override fun handle(event: P2MessageReceiveV1) {
                    try {
                        XLog.i(TAG, "[${channel.displayName}] Message event received: ${Jsons.DEFAULT.toJson(event.event)}")

                        val messageId = event.event.message.messageId
                        val messageType = event.event.message.messageType
                        val createTime = event.event.message.createTime

                        val fiveMinutesInMillis = 5 * 60 * 1000
                        val currentTime = System.currentTimeMillis()
                        if (createTime != null && (currentTime - createTime.toLong() > fiveMinutesInMillis)) {
                            XLog.i(TAG, "[${channel.displayName}] Ignoring message older than 5 minutes: messageId=$messageId")
                            return
                        }

                        if ("text" == messageType) {
                            val rawContent = event.event.message.content
                            val text = try {
                                JSONObject(rawContent).optString("text", "")
                            } catch (e: Exception) {
                                rawContent
                            }
                            lastMessageId = messageId
                            ChannelManager.dispatchMessage(channel, text, messageId)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            })
            .build()
    }

    override fun isConnected(): Boolean = wsClient != null

    override fun init() {
        if (appId.isEmpty() || appSecret.isEmpty()) {
            XLog.w(TAG, "FeiShu AppId/AppSecret not configured, FeiShu channel will be unavailable")
            return
        }

        apiClient = com.lark.oapi.Client.newBuilder(appId, appSecret).build()

        wsClient = FeishuWsClient.Builder(appId, appSecret)
            .eventHandler(eventHandler)
            .build()

        scope.launch {
            try {
                wsClient?.start()
                XLog.i(TAG, "FeiShu WebSocket client started")
            } catch (e: Exception) {
                XLog.e(TAG, "FeiShu WebSocket client failed to start", e)
            }
        }
    }

    override fun disconnect() {
        val oldWsClient = wsClient ?: return
        wsClient = null
        apiClient = null
        lastMessageId = null

        try {
            val autoReconnectField = oldWsClient.javaClass.getDeclaredField("autoReconnect")
            autoReconnectField.isAccessible = true
            autoReconnectField.set(oldWsClient, false)
        } catch (e: Exception) {
            XLog.w(TAG, "FeiShu: failed to disable auto-reconnect (field may have changed)", e)
        }

        try {
            val disconnectMethod = oldWsClient.javaClass.getDeclaredMethod("disconnect")
            disconnectMethod.isAccessible = true
            disconnectMethod.invoke(oldWsClient)
        } catch (e: Exception) {
            XLog.w(TAG, "FeiShu: disconnect call failed (method may have changed)", e)
        }

        try {
            val executorField = oldWsClient.javaClass.getDeclaredField("executor")
            executorField.isAccessible = true
            val executor = executorField.get(oldWsClient) as? java.util.concurrent.ExecutorService
            executor?.shutdownNow()
        } catch (e: Exception) {
            XLog.w(TAG, "FeiShu: failed to shut down thread pool (field may have changed)", e)
        }

        XLog.i(TAG, "FeiShu WebSocket client disconnected")
    }

    override fun reinitFromStorage() {
        disconnect()
        appId = KVUtils.getFeishuAppId()
        appSecret = KVUtils.getFeishuAppSecret()
        init()
    }

    override fun sendMessage(content: String, messageID: String) {
        val client = apiClient
        if (client == null) {
            XLog.w(TAG, "FeiShu reply failed: client not initialized")
            return
        }

        scope.launch {
            try {
                val isMarkdown = containsMarkdown(content)
                val msgType = if (isMarkdown) "post" else "text"
                val jsonContent = if (isMarkdown) buildPostJson(content) else buildTextJson(content)

                val resp = client.im().message().reply(
                    ReplyMessageReq.newBuilder()
                        .messageId(messageID)
                        .replyMessageReqBody(
                            ReplyMessageReqBody.newBuilder()
                                .msgType(msgType)
                                .content(jsonContent)
                                .build()
                        )
                        .build()
                )
                XLog.i(TAG, "FeiShu reply response: code=${resp.code}, msg=${resp.msg}, type=$msgType")
            } catch (e: Exception) {
                XLog.e(TAG, "FeiShu reply failed", e)
            }
        }
    }

    override fun sendImage(imageBytes: ByteArray, messageID: String) {
        scope.launch {
            try {
                val imageKey = uploadImage(imageBytes)
                if (imageKey != null) {
                    replyImage(imageKey, messageID)
                } else {
                    XLog.e(TAG, "FeiShu image upload failed: imageKey is empty")
                }
            } catch (e: Exception) {
                XLog.e(TAG, "FeiShu image send failed", e)
            }
        }
    }

    override fun sendFile(file: File, messageID: String) {
        val client = apiClient
        if (client == null) {
            XLog.w(TAG, "FeiShu file send failed: client not initialized")
            return
        }

        scope.launch {
            try {
                val isImage = file.name.let {
                    it.endsWith(".png", true) || it.endsWith(".jpg", true)
                            || it.endsWith(".jpeg", true) || it.endsWith(".gif", true)
                            || it.endsWith(".bmp", true)
                }

                if (isImage) {
                    val uploadResp = client.im().image().create(
                        CreateImageReq.newBuilder()
                            .createImageReqBody(
                                CreateImageReqBody.newBuilder()
                                    .imageType("message")
                                    .image(file)
                                    .build()
                            )
                            .build()
                    )
                    if (uploadResp.success()) {
                        val content = JSONObject().put("image_key", uploadResp.data.imageKey).toString()
                        client.im().message().reply(
                            ReplyMessageReq.newBuilder()
                                .messageId(messageID)
                                .replyMessageReqBody(
                                    ReplyMessageReqBody.newBuilder()
                                        .msgType("image")
                                        .content(content)
                                        .build()
                                )
                                .build()
                        )
                        XLog.i(TAG, "FeiShu image sent successfully: ${file.name}")
                    } else {
                        XLog.e(TAG, "FeiShu image upload failed: code=${uploadResp.code}, msg=${uploadResp.msg}")
                    }
                } else {
                    val uploadResp = client.im().file().create(
                        com.lark.oapi.service.im.v1.model.CreateFileReq.newBuilder()
                            .createFileReqBody(
                                com.lark.oapi.service.im.v1.model.CreateFileReqBody.newBuilder()
                                    .fileType("stream")
                                    .fileName(file.name)
                                    .file(file)
                                    .build()
                            )
                            .build()
                    )
                    if (uploadResp.success()) {
                        val content = JSONObject().put("file_key", uploadResp.data.fileKey).toString()
                        client.im().message().reply(
                            ReplyMessageReq.newBuilder()
                                .messageId(messageID)
                                .replyMessageReqBody(
                                    ReplyMessageReqBody.newBuilder()
                                        .msgType("file")
                                        .content(content)
                                        .build()
                                )
                                .build()
                        )
                        XLog.i(TAG, "FeiShu file sent successfully: ${file.name}")
                    } else {
                        XLog.e(TAG, "FeiShu file upload failed: code=${uploadResp.code}, msg=${uploadResp.msg}")
                    }
                }
            } catch (e: Exception) {
                XLog.e(TAG, "FeiShu file send failed", e)
            }
        }
    }

    // ---------- Internal helper methods ----------

    private fun uploadImage(imageBytes: ByteArray): String? {
        val client = apiClient ?: return null
        val tempFile = File.createTempFile("feishu_img_", ".png").apply {
            writeBytes(imageBytes)
            deleteOnExit()
        }
        return try {
            val resp = client.im().image().create(
                CreateImageReq.newBuilder()
                    .createImageReqBody(
                        CreateImageReqBody.newBuilder()
                            .imageType("message")
                            .image(tempFile)
                            .build()
                    )
                    .build()
            )
            if (resp.success()) {
                XLog.i(TAG, "FeiShu image uploaded successfully: imageKey=${resp.data.imageKey}")
                resp.data.imageKey
            } else {
                XLog.e(TAG, "FeiShu image upload failed: code=${resp.code}, msg=${resp.msg}")
                null
            }
        } catch (e: Exception) {
            XLog.e(TAG, "FeiShu image upload exception", e)
            null
        } finally {
            tempFile.delete()
        }
    }

    private fun replyImage(imageKey: String, messageID: String) {
        val client = apiClient
        if (client == null) {
            XLog.w(TAG, "FeiShu image reply failed: client not initialized")
            return
        }
        scope.launch {
            try {
                val content = JSONObject().put("image_key", imageKey).toString()
                val resp = client.im().message().reply(
                    ReplyMessageReq.newBuilder()
                        .messageId(messageID)
                        .replyMessageReqBody(
                            ReplyMessageReqBody.newBuilder()
                                .msgType("image")
                                .content(content)
                                .build()
                        )
                        .build()
                )
                XLog.i(TAG, "FeiShu image reply response: code=${resp.code}, msg=${resp.msg}")
            } catch (e: Exception) {
                XLog.e(TAG, "FeiShu image reply failed", e)
            }
        }
    }

    private val markdownPatterns = listOf(
        Regex("""\*\*.+?\*\*"""),
        Regex("""^#{1,6}\s""", RegexOption.MULTILINE),
        Regex("""```"""),
        Regex("""\[.+?]\(.+?\)"""),
        Regex("""^\|.+\|.+\|""", RegexOption.MULTILINE),
        Regex("""~~.+?~~"""),
        Regex("""^>\s""", RegexOption.MULTILINE),
        Regex("""^- \[[ x]]""", RegexOption.MULTILINE),
    )

    private fun containsMarkdown(text: String): Boolean =
        markdownPatterns.any { it.containsMatchIn(text) }

    private fun buildPostJson(content: String): String {
        val postContent = org.json.JSONArray().apply {
            put(org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "md")
                    put("text", content)
                })
            })
        }
        return JSONObject().apply {
            put("zh_cn", JSONObject().apply {
                put("content", postContent)
            })
        }.toString()
    }

    private fun buildTextJson(content: String): String =
        JSONObject().put("text", content).toString()

    companion object {
        private const val TAG = "FeiShuHandler"
    }
}
