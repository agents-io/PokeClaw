// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel

/**
 * 各消息通道的统一接口。
 * 每个通道（钉钉、飞书、QQ、Discord、Telegram）各自实现此接口。
 */
interface ChannelHandler {

    val channel: Channel

    /** 当前通道是否已连接/正在运行 */
    fun isConnected(): Boolean

    fun init()

    fun disconnect()

    fun reinitFromStorage()

    fun sendMessage(content: String, messageID: String)

    fun sendImage(imageBytes: ByteArray, messageID: String)

    fun sendFile(file: java.io.File, messageID: String)

    /** 立即发送缓冲区中的所有待发消息。默认无操作，有缓冲机制的通道（如微信）需覆写。 */
    fun flushMessages() {}

    /**
     * 获取最近一次收到消息的发送者标识。
     * 各通道返回各自的用户标识（Telegram→chatId、QQ→openId、Discord→channelId、
     * DingTalk→staffId、WeChat→userId、FeiShu→messageId）。
     * 用于定时任务持久化目标用户。
     */
    fun getLastSenderId(): String? = null

    /**
     * 按用户标识主动发送消息（不依赖 messageID 上下文）。
     * 用于定时任务触发后向用户推送消息。
     * 不支持主动发送的通道（如飞书）默认走 sendMessage 降级。
     */
    fun sendMessageToUser(userId: String, content: String) {
        sendMessage(content, "")
    }

    /**
     * 恢复路由上下文：让后续 sendMessage("", content) 调用能正确路由到目标用户。
     * 定时任务触发时、在 startNewTask 之前调用。
     * 各通道根据自己的路由机制设置内部状态（如 Telegram 设置 lastChatId 等）。
     */
    fun restoreRoutingContext(targetUserId: String) {}
}
