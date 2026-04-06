package io.agents.pokeclaw.channel.discord

import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.channel.ChannelHandler
import io.agents.pokeclaw.channel.ChannelManager
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class DiscordChannelHandler(
    private val scope: CoroutineScope,
    private var botToken: String,
) : ChannelHandler {

    override val channel = Channel.DISCORD

    @Volatile
    private var lastChannelId: String? = null

    private val callback = object : DiscordCallback<String> {
        override fun onSuccess(result: String) { XLog.i(TAG, "Discord 回复成功: ${result.take(120)}") }
        override fun onFailure(error: String) { XLog.e(TAG, "Discord 回复失败: $error") }
    }

    override fun isConnected(): Boolean = DiscordGatewayClient.getInstance().isConnected()

    override fun init() {
        if (botToken.isEmpty()) {
            XLog.w(TAG, "Discord Bot Token 未配置，Discord 通道将不可用")
            return
        }

        DiscordApiClient.getInstance().init(botToken)
        DiscordGatewayClient.getInstance().setOnDiscordMessageListener(
            object : DiscordGatewayClient.OnDiscordMessageListener {
                override fun onDiscordMessage(channelId: String, messageId: String, content: String) {
                    lastChannelId = channelId
                    XLog.i(TAG, "[${channel.displayName}] 收到消息: $content, channelId=$channelId")
                    ChannelManager.dispatchMessage(channel, content, messageId)
                }
            }
        )
        scope.launch {
            try {
                DiscordGatewayClient.getInstance().start(botToken)
                XLog.i(TAG, "Discord Gateway 已启动")
            } catch (e: Exception) {
                XLog.e(TAG, "Discord Gateway 启动失败", e)
            }
        }
    }

    override fun disconnect() {
        try {
            DiscordGatewayClient.getInstance().setOnDiscordMessageListener(null)
            DiscordGatewayClient.getInstance().stop()
            lastChannelId = null
            XLog.i(TAG, "Discord Gateway 已断开")
        } catch (e: Exception) {
            XLog.w(TAG, "Discord 断开时异常", e)
        }
    }

    override fun reinitFromStorage() {
        disconnect()
        botToken = KVUtils.getDiscordBotToken()
        init()
    }

    override fun sendMessage(content: String, messageID: String) {
        val channelId = lastChannelId
        if (channelId.isNullOrEmpty()) {
            XLog.w(TAG, "Discord 回复失败：没有可用的 channelId")
            return
        }
        if (content.isBlank()) {
            XLog.w(TAG, "Discord 跳过空消息")
            return
        }
        scope.launch {
            try {
                DiscordApiClient.getInstance().sendMessage(channelId, content, callback)
            } catch (e: Exception) {
                XLog.e(TAG, "Discord 回复失败", e)
            }
        }
    }

    override fun sendImage(imageBytes: ByteArray, messageID: String) {
        val channelId = lastChannelId ?: return
        scope.launch {
            try {
                DiscordApiClient.getInstance().sendImage(channelId, imageBytes, callback = callback)
            } catch (e: Exception) {
                XLog.e(TAG, "Discord 发送图片失败", e)
            }
        }
    }

    override fun sendFile(file: java.io.File, messageID: String) {
        val channelId = lastChannelId ?: return
        scope.launch {
            try {
                DiscordApiClient.getInstance().sendFile(
                    channelId, file.readBytes(), file.name,
                    callback = callback
                )
            } catch (e: Exception) {
                XLog.e(TAG, "Discord 发送文件失败", e)
            }
        }
    }

    override fun getLastSenderId(): String? = lastChannelId

    override fun restoreRoutingContext(targetUserId: String) {
        if (targetUserId.isNotEmpty()) lastChannelId = targetUserId
    }

    override fun sendMessageToUser(userId: String, content: String) {
        if (userId.isEmpty() || content.isBlank()) return
        scope.launch {
            try {
                DiscordApiClient.getInstance().sendMessage(userId, content, callback)
            } catch (e: Exception) {
                XLog.e(TAG, "Discord sendMessageToUser 失败", e)
            }
        }
    }

    companion object {
        private const val TAG = "DiscordHandler"
    }
}
