package com.apk.claw.android.channel

import com.apk.claw.android.channel.dingtalk.DingTalkChannelHandler
import com.apk.claw.android.channel.discord.DiscordChannelHandler
import com.apk.claw.android.channel.feishu.FeiShuChannelHandler
import com.apk.claw.android.channel.qqbot.QQChannelHandler
import com.apk.claw.android.channel.telegram.TelegramChannelHandler
import com.apk.claw.android.channel.wechat.WeChatChannelHandler
import com.apk.claw.android.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

enum class Channel(val displayName: String) {
    DINGTALK("DingTalk"),
    FEISHU("FeiShu"),
    QQ("QQ"),
    DISCORD("Discord"),
    TELEGRAM("Telegram"),
    WECHAT("WeChat"),
    LOCAL("Local"),
}

object ChannelManager {

    private const val TAG = "ChannelManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient()

    private val handlers = mutableMapOf<Channel, ChannelHandler>()
    private var messageListener: OnMessageReceivedListener? = null

    /**
     * 收到消息的回调接口
     */
    interface OnMessageReceivedListener {
        fun onMessageReceived(channel: Channel, message: String, messageID: String)
    }

    @JvmStatic
    fun setOnMessageReceivedListener(listener: OnMessageReceivedListener?) {
        this.messageListener = listener
    }

    /**
     * 初始化所有通道，在 Application.onCreate() 中调用即可。
     */
    @JvmStatic
    @JvmOverloads
    fun init(
        dingtalkAppKey: String? = null,
        dingtalkAppSecret: String? = null,
        feishuAppId: String? = null,
        feishuAppSecret: String? = null,
        qqAppId: String? = null,
        qqAppSecret: String? = null,
        discordBotToken: String? = null,
        telegramBotToken: String? = null,
        wechatBotToken: String? = null,
        wechatApiBaseUrl: String? = null
    ) {
        handlers[Channel.DINGTALK] = DingTalkChannelHandler(
            scope, httpClient,
            dingtalkAppKey?.takeIf { it.isNotEmpty() } ?: "",
            dingtalkAppSecret?.takeIf { it.isNotEmpty() } ?: "",
        )
        handlers[Channel.FEISHU] = FeiShuChannelHandler(
            scope,
            feishuAppId?.takeIf { it.isNotEmpty() } ?: "",
            feishuAppSecret?.takeIf { it.isNotEmpty() } ?: "",
        )
        handlers[Channel.QQ] = QQChannelHandler(
            scope,
            qqAppId?.takeIf { it.isNotEmpty() } ?: "",
            qqAppSecret?.takeIf { it.isNotEmpty() } ?: "",
        )
        handlers[Channel.DISCORD] = DiscordChannelHandler(
            scope,
            discordBotToken?.takeIf { it.isNotEmpty() } ?: "",
        )
        handlers[Channel.TELEGRAM] = TelegramChannelHandler(
            scope, httpClient,
            telegramBotToken?.takeIf { it.isNotEmpty() } ?: "",
        )
        handlers[Channel.WECHAT] = WeChatChannelHandler(
            scope,
            wechatBotToken?.takeIf { it.isNotEmpty() } ?: "",
            wechatApiBaseUrl?.takeIf { it.isNotEmpty() } ?: "",
        )

        handlers.values.forEach { it.init() }
        XLog.i(TAG, "ChannelManager 初始化完成")
    }

    /**
     * 从 MMKV 重新读取配置并初始化所有通道（用户保存配置后调用）
     */
    @JvmStatic
    fun reinitFromStorage() {
        handlers.values.forEach { it.reinitFromStorage() }
    }

    /**
     * 仅重连未连接的通道（网络恢复时调用，不影响已正常运行的通道）
     */
    @JvmStatic
    fun reconnectIfNeeded() {
        handlers.forEach { (channel, handler) ->
            if (!handler.isConnected()) {
                XLog.i(TAG, "重连${channel.displayName}通道")
                handler.reinitFromStorage()
            }
        }
    }

    @JvmStatic
    fun reinitDingTalkFromStorage() {
        handlers[Channel.DINGTALK]?.reinitFromStorage()
    }

    @JvmStatic
    fun reinitFeiShuFromStorage() {
        handlers[Channel.FEISHU]?.reinitFromStorage()
    }

    @JvmStatic
    fun reinitQQFromStorage() {
        handlers[Channel.QQ]?.reinitFromStorage()
    }

    @JvmStatic
    fun reinitDiscordFromStorage() {
        handlers[Channel.DISCORD]?.reinitFromStorage()
    }

    @JvmStatic
    fun reinitTelegramFromStorage() {
        handlers[Channel.TELEGRAM]?.reinitFromStorage()
    }

    @JvmStatic
    fun reinitWeChatFromStorage() {
        handlers[Channel.WECHAT]?.reinitFromStorage()
    }

    @JvmStatic
    fun disconnectAll() {
        handlers.forEach { (channel, handler) ->
            if (handler.isConnected()) {
                XLog.i(TAG, "断开${channel.displayName}通道")
                handler.disconnect()
            }
        }
    }

    @JvmStatic
    fun sendMessage(channel: Channel, content: String, messageID: String) {
        val trimmedContent = content.trim('\n', '\r')
        if (trimmedContent.isBlank()) {
            XLog.w(TAG, "sendMessage 跳过空消息 [${channel.displayName}]")
            return
        }
        XLog.d(TAG, "sendMessage [${channel.displayName}]: ${trimmedContent.take(120)}")
        handlers[channel]?.sendMessage(trimmedContent, messageID)
    }

    @JvmStatic
    fun sendImage(channel: Channel, imageBytes: ByteArray, messageID: String) {
        handlers[channel]?.sendImage(imageBytes, messageID)
    }

    @JvmStatic
    fun sendFile(channel: Channel, file: java.io.File, messageID: String) {
        XLog.i(TAG, "sendFile: ${file.name} via ${channel.displayName}")
        handlers[channel]?.sendFile(file, messageID)
    }

    /**
     * 立即发送指定通道缓冲区中的待发消息。任务结束时调用。
     */
    @JvmStatic
    fun flushMessages(channel: Channel) {
        handlers[channel]?.flushMessages()
    }

    /**
     * 恢复指定通道的路由上下文，在定时任务执行前调用。
     */
    @JvmStatic
    fun restoreRoutingContext(channel: Channel, targetUserId: String) {
        handlers[channel]?.restoreRoutingContext(targetUserId)
    }

    /**
     * 获取指定通道最近一次消息发送者的标识，用于定时任务持久化。
     */
    @JvmStatic
    fun getLastSenderId(channel: Channel): String? {
        return handlers[channel]?.getLastSenderId()
    }

    /**
     * 按用户标识主动发送消息（不依赖 messageID 上下文），用于定时任务触发。
     */
    @JvmStatic
    fun sendMessageToUser(channel: Channel, userId: String, content: String) {
        val trimmedContent = content.trim('\n', '\r')
        if (trimmedContent.isBlank()) return
        XLog.d(TAG, "sendMessageToUser [${channel.displayName}] userId=${userId.take(20)}: ${trimmedContent.take(120)}")
        handlers[channel]?.sendMessageToUser(userId, trimmedContent)
    }

    /**
     * 供各 ChannelHandler 内部调用，将收到的消息分发给注册的监听器。
     */
    @JvmStatic
    fun dispatchMessage(channel: Channel, message: String, messageID: String) {
        messageListener?.onMessageReceived(channel, message, messageID)
    }
}
