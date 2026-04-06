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
                        XLog.i(TAG, "[${channel.displayName}] 收到消息事件: ${Jsons.DEFAULT.toJson(event.event)}")

                        val messageId = event.event.message.messageId
                        val messageType = event.event.message.messageType
                        val createTime = event.event.message.createTime

                        val fiveMinutesInMillis = 5 * 60 * 1000
                        val currentTime = System.currentTimeMillis()
                        if (createTime != null && (currentTime - createTime.toLong() > fiveMinutesInMillis)) {
                            XLog.i(TAG, "[${channel.displayName}] 忽略超过5分钟的消息: messageId=$messageId")
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
            XLog.w(TAG, "飞书 AppId/AppSecret 未配置，飞书通道将不可用")
            return
        }

        apiClient = com.lark.oapi.Client.newBuilder(appId, appSecret).build()

        wsClient = FeishuWsClient.Builder(appId, appSecret)
            .eventHandler(eventHandler)
            .build()

        scope.launch {
            try {
                wsClient?.start()
                XLog.i(TAG, "飞书 WebSocket 客户端已启动")
            } catch (e: Exception) {
                XLog.e(TAG, "飞书 WebSocket 客户端启动失败", e)
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
            XLog.w(TAG, "飞书: 禁用自动重连失败(字段可能已变更)", e)
        }

        try {
            val disconnectMethod = oldWsClient.javaClass.getDeclaredMethod("disconnect")
            disconnectMethod.isAccessible = true
            disconnectMethod.invoke(oldWsClient)
        } catch (e: Exception) {
            XLog.w(TAG, "飞书: 调用 disconnect 失败(方法可能已变更)", e)
        }

        try {
            val executorField = oldWsClient.javaClass.getDeclaredField("executor")
            executorField.isAccessible = true
            val executor = executorField.get(oldWsClient) as? java.util.concurrent.ExecutorService
            executor?.shutdownNow()
        } catch (e: Exception) {
            XLog.w(TAG, "飞书: 关闭线程池失败(字段可能已变更)", e)
        }

        XLog.i(TAG, "飞书 WebSocket 客户端已断开")
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
            XLog.w(TAG, "飞书回复失败：客户端未初始化")
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
                XLog.i(TAG, "飞书回复响应: code=${resp.code}, msg=${resp.msg}, type=$msgType")
            } catch (e: Exception) {
                XLog.e(TAG, "飞书回复失败", e)
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
                    XLog.e(TAG, "飞书图片上传失败，imageKey 为空")
                }
            } catch (e: Exception) {
                XLog.e(TAG, "飞书发送图片失败", e)
            }
        }
    }

    override fun sendFile(file: File, messageID: String) {
        val client = apiClient
        if (client == null) {
            XLog.w(TAG, "飞书发送文件失败：客户端未初始化")
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
                        XLog.i(TAG, "飞书图片发送成功: ${file.name}")
                    } else {
                        XLog.e(TAG, "飞书图片上传失败: code=${uploadResp.code}, msg=${uploadResp.msg}")
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
                        XLog.i(TAG, "飞书文件发送成功: ${file.name}")
                    } else {
                        XLog.e(TAG, "飞书文件上传失败: code=${uploadResp.code}, msg=${uploadResp.msg}")
                    }
                }
            } catch (e: Exception) {
                XLog.e(TAG, "飞书发送文件失败", e)
            }
        }
    }

    // ---------- 内部工具方法 ----------

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
                XLog.i(TAG, "飞书图片上传成功: imageKey=${resp.data.imageKey}")
                resp.data.imageKey
            } else {
                XLog.e(TAG, "飞书图片上传失败: code=${resp.code}, msg=${resp.msg}")
                null
            }
        } catch (e: Exception) {
            XLog.e(TAG, "飞书图片上传异常", e)
            null
        } finally {
            tempFile.delete()
        }
    }

    private fun replyImage(imageKey: String, messageID: String) {
        val client = apiClient
        if (client == null) {
            XLog.w(TAG, "飞书图片回复失败：客户端未初始化")
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
                XLog.i(TAG, "飞书图片回复响应: code=${resp.code}, msg=${resp.msg}")
            } catch (e: Exception) {
                XLog.e(TAG, "飞书图片回复失败", e)
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
