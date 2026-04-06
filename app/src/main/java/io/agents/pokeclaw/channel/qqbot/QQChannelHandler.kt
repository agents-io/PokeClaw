// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel.qqbot

import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.channel.ChannelHandler
import io.agents.pokeclaw.channel.ChannelManager
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class QQChannelHandler(
    private val scope: CoroutineScope,
    private var appId: String,
    private var appSecret: String,
) : ChannelHandler {

    override val channel = Channel.QQ

    @Volatile
    private var lastOpenId: String? = null
    @Volatile
    private var lastIsGroup: Boolean = false
    @Volatile
    private var lastMessageId: String? = null
    @Volatile
    private var lastMsgSeq: Int = 0

    private val callback = object : QBotCallback<String> {
        override fun onSuccess(result: String) { XLog.i(TAG, "QQ reply succeeded: ${result.take(120)}") }
        override fun onFailure(e: QBotException) { XLog.e(TAG, "QQ reply failed", e) }
    }

    override fun isConnected(): Boolean = QBotWebSocketManager.getInstance().isConnected

    override fun init() {
        if (appId.isEmpty() || appSecret.isEmpty()) {
            XLog.w(TAG, "QQ AppId/AppSecret not configured, QQ channel will be unavailable")
            return
        }

        QBotApiClient.getInstance().init(ClawApplication.instance)
        QBotWebSocketManager.getInstance().setOnQQMessageListener { isGroup, openId, messageId, content ->
            lastOpenId = openId
            lastIsGroup = isGroup
            lastMessageId = messageId
            lastMsgSeq = 0
            XLog.i(TAG, "[${channel.displayName}] Message received: $content, isGroup=$isGroup, openId=$openId")
            ChannelManager.dispatchMessage(channel, content, messageId)
        }
        scope.launch {
            try {
                QBotWebSocketManager.getInstance().start()
                XLog.i(TAG, "QQ WebSocket started")
            } catch (e: Exception) {
                XLog.e(TAG, "QQ WebSocket failed to start", e)
            }
        }
    }

    override fun disconnect() {
        try {
            QBotWebSocketManager.getInstance().setOnQQMessageListener(null)
            QBotWebSocketManager.getInstance().stop()
            lastOpenId = null
            lastIsGroup = false
            lastMessageId = null
            lastMsgSeq = 0
            XLog.i(TAG, "QQ WebSocket disconnected")
        } catch (e: Exception) {
            XLog.w(TAG, "Exception on QQ disconnect", e)
        }
    }

    override fun reinitFromStorage() {
        disconnect()
        appId = KVUtils.getQqAppId()
        appSecret = KVUtils.getQqAppSecret()
        init()
    }

    override fun sendMessage(content: String, messageID: String) {
        val openId = lastOpenId
        if (openId.isNullOrEmpty()) {
            XLog.w(TAG, "QQ reply failed: no available session openId")
            return
        }
        if (content.isBlank()) {
            XLog.w(TAG, "QQ skipping empty message")
            return
        }
        val msgId = lastMessageId
        val seq = nextMsgSeq()
        scope.launch {
            try {
                val api = QBotApiClient.getInstance()
                if (lastIsGroup) {
                    api.sendGroupMessage(openId, content, 0, msgId, seq, callback)
                } else {
                    api.sendC2CMessage(openId, content, 0, msgId, seq, callback)
                }
            } catch (e: Exception) {
                XLog.e(TAG, "QQ reply failed", e)
            }
        }
    }

    override fun sendImage(imageBytes: ByteArray, messageID: String) {
        val openId = lastOpenId ?: return
        val msgId = lastMessageId
        val seq = nextMsgSeq()
        scope.launch {
            try {
                QBotApiClient.getInstance().uploadFileAndSend(
                    openId, lastIsGroup,
                    QBotApiClient.FILE_TYPE_IMAGE, imageBytes,
                    msgId, seq, callback
                )
            } catch (e: Exception) {
                XLog.e(TAG, "QQ image send failed", e)
            }
        }
    }

    override fun sendFile(file: java.io.File, messageID: String) {
        val openId = lastOpenId ?: return
        val msgId = lastMessageId
        val seq = nextMsgSeq()
        scope.launch {
            try {
                val fileType = resolveFileType(file.extension.lowercase())
                QBotApiClient.getInstance().uploadFileAndSend(
                    openId, lastIsGroup, fileType, file.readBytes(),
                    file.name, msgId, seq, callback
                )
            } catch (e: Exception) {
                XLog.e(TAG, "QQ file send failed", e)
            }
        }
    }

    // ---------- Internal helper methods ----------

    private fun nextMsgSeq(): Int = ++lastMsgSeq

    private fun resolveFileType(ext: String): Int = when (ext) {
        "png", "jpg", "jpeg", "gif", "bmp", "webp" -> QBotApiClient.FILE_TYPE_IMAGE
        "mp4", "avi", "mov", "mkv", "flv", "wmv"   -> QBotApiClient.FILE_TYPE_VIDEO
        "amr", "silk", "wav", "mp3", "ogg", "aac"   -> QBotApiClient.FILE_TYPE_VOICE
        else                                         -> QBotApiClient.FILE_TYPE_FILE
    }

    override fun getLastSenderId(): String? {
        val openId = lastOpenId ?: return null
        val prefix = if (lastIsGroup) "group" else "c2c"
        return "$prefix:$openId"
    }

    override fun restoreRoutingContext(targetUserId: String) {
        val parts = targetUserId.split(":", limit = 2)
        if (parts.size == 2) {
            lastIsGroup = parts[0] == "group"
            lastOpenId = parts[1]
            lastMessageId = null
        }
    }

    override fun sendMessageToUser(userId: String, content: String) {
        if (userId.isEmpty() || content.isBlank()) return
        val parts = userId.split(":", limit = 2)
        if (parts.size != 2) {
            XLog.w(TAG, "QQ sendMessageToUser failed: invalid userId format: $userId")
            return
        }
        val isGroup = parts[0] == "group"
        val openId = parts[1]
        val seq = nextMsgSeq()
        scope.launch {
            try {
                val api = QBotApiClient.getInstance()
                if (isGroup) {
                    api.sendGroupMessage(openId, content, 0, null, seq, callback)
                } else {
                    api.sendC2CMessage(openId, content, 0, null, seq, callback)
                }
            } catch (e: Exception) {
                XLog.e(TAG, "QQ sendMessageToUser failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "QQHandler"
    }
}
