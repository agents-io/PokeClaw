// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.content.Context
import io.agents.pokeclaw.utils.XLog
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File

/**
 * Manages conversation memory compaction.
 *
 * Architecture:
 * - Compacts only on natural pauses (switch chatroom, app pause, new chat)
 * - Never compacts during active chat (avoids LiteRT-LM single-session conflict)
 * - Writes .memory.md digest file per conversation
 * - On chatroom switch: reads .memory.md + last N messages → system prompt
 *
 * Validated approach:
 * - Google ADK: compaction_interval=3, overlap_size=1
 * - Claude Code: auto-compact at ~95% context
 * - Khoj: 3-layer memory (short/medium/long-term)
 */
object ConversationCompactor {

    private const val TAG = "Compactor"
    private const val COMPACT_CHAR_THRESHOLD = 8000 // ~2000 tokens, ~50% of 32K
    private const val KEEP_RECENT_MESSAGES = 5

    /**
     * Check if conversation needs compaction.
     */
    fun needsCompaction(messages: List<ChatMessage>): Boolean {
        val totalChars = messages.sumOf { it.content.length }
        return totalChars > COMPACT_CHAR_THRESHOLD && messages.size > KEEP_RECENT_MESSAGES + 3
    }

    /**
     * Compact a conversation: summarize older messages, save digest.
     * Must be called when LLM session is available (not during active chat).
     *
     * @param engine LiteRT-LM engine (already initialized)
     * @param messages all conversation messages
     * @param context Android context for file access
     * @param conversationId conversation identifier
     * @return the digest text, or null if compaction not needed/failed
     */
    fun compact(
        engine: Engine,
        messages: List<ChatMessage>,
        context: Context,
        conversationId: String
    ): String? {
        if (!needsCompaction(messages)) return null

        val olderMessages = messages.dropLast(KEEP_RECENT_MESSAGES)
        val olderText = buildString {
            olderMessages.forEach { msg ->
                when (msg.role) {
                    ChatMessage.Role.USER -> appendLine("User: ${msg.content}")
                    ChatMessage.Role.ASSISTANT -> appendLine("Assistant: ${msg.content}")
                    else -> {}
                }
            }
        }

        if (olderText.isBlank()) return null

        try {
            // Create temporary conversation for summarization
            val tempConversation = engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of("You are a summarization assistant. Summarize conversations concisely, preserving key facts, decisions, and action items."),
                    samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.3)
                )
            )

            val prompt = "Summarize this conversation in 3-5 sentences. Keep names, dates, decisions, and action items:\n\n$olderText"
            val response = tempConversation.sendMessage(prompt)
            val digest = response?.toString()?.trim() ?: return null

            tempConversation.close()

            // Save digest to .memory.md
            saveDigest(context, conversationId, digest, messages)

            XLog.i(TAG, "Compacted: ${olderMessages.size} messages → ${digest.length} chars digest")
            return digest
        } catch (e: Exception) {
            XLog.e(TAG, "Compaction failed", e)
            return null
        }
    }

    /**
     * Save digest to .memory.md file alongside the conversation .md file.
     */
    private fun saveDigest(context: Context, conversationId: String, digest: String, messages: List<ChatMessage>) {
        val chatDir = File(context.getExternalFilesDir(null), "chats")
        if (!chatDir.exists()) chatDir.mkdirs()

        // Find the conversation's .md file
        val mdFile = chatDir.listFiles()?.find { file ->
            file.extension == "md" && !file.name.endsWith(".memory.md") &&
            file.readText().contains("id: $conversationId")
        }

        val memoryFile = if (mdFile != null) {
            File(chatDir, mdFile.nameWithoutExtension + ".memory.md")
        } else {
            File(chatDir, "$conversationId.memory.md")
        }

        val content = buildString {
            appendLine("---")
            appendLine("conversation: $conversationId")
            appendLine("compacted_at: ${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
            appendLine("original_messages: ${messages.size}")
            appendLine("---")
            appendLine()
            appendLine(digest)
        }

        memoryFile.writeText(content)
    }

    /**
     * Load digest for a conversation.
     * Returns null if no digest exists.
     */
    fun loadDigest(context: Context, conversationId: String): String? {
        val chatDir = File(context.getExternalFilesDir(null), "chats")
        if (!chatDir.exists()) return null

        // Find .memory.md file for this conversation
        val memoryFile = chatDir.listFiles()?.find { file ->
            file.name.endsWith(".memory.md") && file.readText().contains("conversation: $conversationId")
        } ?: return null

        // Extract digest (everything after frontmatter)
        val lines = memoryFile.readLines()
        var pastFrontmatter = false
        var frontmatterCount = 0
        val digest = StringBuilder()

        for (line in lines) {
            if (line == "---") {
                frontmatterCount++
                if (frontmatterCount >= 2) { pastFrontmatter = true; continue }
                continue
            }
            if (pastFrontmatter && line.isNotBlank()) {
                if (digest.isNotEmpty()) digest.appendLine()
                digest.append(line)
            }
        }

        return digest.toString().trim().ifEmpty { null }
    }

    /**
     * Build system prompt with conversation context for chatroom switch.
     */
    fun buildRestoredSystemPrompt(
        context: Context,
        conversationId: String,
        recentMessages: List<ChatMessage>
    ): String {
        val digest = loadDigest(context, conversationId)
        val recentText = buildString {
            recentMessages.forEach { msg ->
                when (msg.role) {
                    ChatMessage.Role.USER -> appendLine("User: ${msg.content}")
                    ChatMessage.Role.ASSISTANT -> appendLine("Assistant: ${msg.content}")
                    else -> {}
                }
            }
        }

        return buildString {
            appendLine("You are a helpful AI assistant on an Android phone.")
            if (digest != null) {
                appendLine()
                appendLine("Summary of our earlier conversation:")
                appendLine(digest)
            }
            if (recentText.isNotBlank()) {
                appendLine()
                appendLine("Most recent messages:")
                appendLine(recentText)
            }
            appendLine()
            appendLine("Continue naturally from here.")
        }
    }
}
