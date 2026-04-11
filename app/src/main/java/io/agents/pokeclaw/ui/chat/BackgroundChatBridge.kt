// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class BackgroundChatEvent(
    val conversationId: String,
    val message: ChatMessage,
)

/**
 * Minimal boundary for background automations to report into the current chatroom
 * without importing Compose/UI controllers directly.
 */
object BackgroundChatBridge {

    private val _events = MutableSharedFlow<BackgroundChatEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<BackgroundChatEvent> = _events.asSharedFlow()

    fun postSystem(context: Context, conversationId: String, text: String) {
        if (conversationId.isBlank() || text.isBlank()) return
        val message = ChatMessage(
            role = ChatMessage.Role.SYSTEM,
            content = text.trim(),
        )
        ChatHistoryManager.appendMessage(
            context = context,
            conversationId = conversationId,
            message = message,
        )
        _events.tryEmit(BackgroundChatEvent(conversationId, message))
    }
}
