// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

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
        override fun onSuccess(result: String) { XLog.i(TAG, "Discord reply succeeded: ${result.take(120)}") }
        override fun onFailure(error: String) { XLog.e(TAG, "Discord reply failed: $error") }
    }

    override fun isConnected(): Boolean = DiscordGatewayClient.getInstance().isConnected()

    override fun init() {
        if (botToken.isEmpty()) {
            XLog.w(TAG, "Discord Bot Token not configured, Discord channel will be unavailable")
            return
        }

        DiscordApiClient.getInstance().init(botToken)
        DiscordGatewayClient.getInstance().setOnDiscordMessageListener(
            object : DiscordGatewayClient.OnDiscordMessageListener {
                override fun onDiscordMessage(channelId: String, messageId: String, content: String) {
                    lastChannelId = channelId
                    XLog.i(TAG, "[${channel.displayName}] Message received: $content, channelId=$channelId")
                    ChannelManager.dispatchMessage(channel, content, messageId)
                }
            }
        )
        scope.launch {
            try {
                DiscordGatewayClient.getInstance().start(botToken)
                XLog.i(TAG, "Discord Gateway started")
            } catch (e: Exception) {
                XLog.e(TAG, "Discord Gateway failed to start", e)
            }
        }
    }

    override fun disconnect() {
        try {
            DiscordGatewayClient.getInstance().setOnDiscordMessageListener(null)
            DiscordGatewayClient.getInstance().stop()
            lastChannelId = null
            XLog.i(TAG, "Discord Gateway disconnected")
        } catch (e: Exception) {
            XLog.w(TAG, "Exception on Discord disconnect", e)
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
            XLog.w(TAG, "Discord reply failed: no available channelId")
            return
        }
        if (content.isBlank()) {
            XLog.w(TAG, "Discord skipping empty message")
            return
        }
        scope.launch {
            try {
                DiscordApiClient.getInstance().sendMessage(channelId, content, callback)
            } catch (e: Exception) {
                XLog.e(TAG, "Discord reply failed", e)
            }
        }
    }

    override fun sendImage(imageBytes: ByteArray, messageID: String) {
        val channelId = lastChannelId ?: return
        scope.launch {
            try {
                DiscordApiClient.getInstance().sendImage(channelId, imageBytes, callback = callback)
            } catch (e: Exception) {
                XLog.e(TAG, "Discord image send failed", e)
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
                XLog.e(TAG, "Discord file send failed", e)
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
                XLog.e(TAG, "Discord sendMessageToUser failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "DiscordHandler"
    }
}
