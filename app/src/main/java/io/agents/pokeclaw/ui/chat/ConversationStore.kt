// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.content.Context
import io.agents.pokeclaw.utils.KVUtils

/**
 * Owns persisted conversation identity and markdown-backed history operations.
 *
 * This keeps Activity code focused on UI wiring instead of direct KV/markdown glue.
 */
class ConversationStore(
    private val context: Context
) {

    companion object {
        private fun newConversationId(): String = "chat_${System.currentTimeMillis()}"
    }

    data class SessionSnapshot(
        val conversationId: String,
        val messages: List<ChatMessage>,
        val conversations: List<ChatHistoryManager.ConversationSummary>
    )

    var currentConversationId: String = KVUtils.getCurrentConversationId()
        .takeIf { it.isNotEmpty() }
        ?: newConversationId()
        private set

    fun refreshSidebar(): List<ChatHistoryManager.ConversationSummary> {
        return ChatHistoryManager.listConversations(context)
    }

    fun restoreLastConversation(): SessionSnapshot? {
        val conversations = refreshSidebar()
        val match = conversations.firstOrNull { it.id == currentConversationId } ?: return null
        return SessionSnapshot(
            conversationId = currentConversationId,
            messages = ChatHistoryManager.load(match.file),
            conversations = conversations
        )
    }

    fun saveCurrent(messages: List<ChatMessage>, modelName: String): List<ChatHistoryManager.ConversationSummary> {
        ChatHistoryManager.save(context, currentConversationId, messages, modelName)
        persistCurrentConversationId()
        return refreshSidebar()
    }

    fun startNewConversation(
        currentMessages: List<ChatMessage>,
        modelName: String
    ): SessionSnapshot {
        saveCurrent(currentMessages, modelName)
        currentConversationId = newConversationId()
        persistCurrentConversationId()
        return SessionSnapshot(
            conversationId = currentConversationId,
            messages = emptyList(),
            conversations = refreshSidebar()
        )
    }

    fun openConversation(
        target: ChatHistoryManager.ConversationSummary,
        currentMessages: List<ChatMessage>,
        modelName: String
    ): SessionSnapshot {
        saveCurrent(currentMessages, modelName)
        currentConversationId = target.id
        persistCurrentConversationId()
        return SessionSnapshot(
            conversationId = currentConversationId,
            messages = ChatHistoryManager.load(target.file),
            conversations = refreshSidebar()
        )
    }

    fun renameConversation(
        target: ChatHistoryManager.ConversationSummary,
        newTitle: String
    ): Boolean {
        return ChatHistoryManager.rename(target.file, newTitle)
    }

    fun deleteConversation(target: ChatHistoryManager.ConversationSummary): Boolean {
        return ChatHistoryManager.delete(target.file)
    }

    private fun persistCurrentConversationId() {
        KVUtils.setCurrentConversationId(currentConversationId)
    }
}
