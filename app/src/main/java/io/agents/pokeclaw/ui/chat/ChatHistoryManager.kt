// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves and loads chat conversations as markdown files.
 *
 * Storage: /storage/emulated/0/PokeClaw/chats/
 * Format: 2026-04-04-send-hi-to-mom.md
 *
 * Each file:
 * ---
 * title: Send hi to Mom
 * created: 2026-04-04T15:30:00
 * model: FunctionGemma-270M
 * ---
 *
 * ## User
 * Open WhatsApp and send hi to Mom
 *
 * ## 🦞 Assistant
 * On it. Checking your screen.
 *
 * ## System
 * Task completed.
 */
object ChatHistoryManager {

    data class ConversationSummary(
        val id: String,
        val title: String,
        val created: Long,
        val file: File
    )

    private fun getChatDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "chats")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Save a conversation to markdown file.
     */
    fun save(context: Context, conversationId: String, messages: List<ChatMessage>, model: String) {
        if (messages.isEmpty()) return

        // Generate title from first user message
        val firstUserMsg = messages.firstOrNull { it.role == ChatMessage.Role.USER }
        val title = firstUserMsg?.content?.take(50)?.replace(Regex("[^a-zA-Z0-9\\s]"), "")?.trim()?.replace("\\s+".toRegex(), "-")?.lowercase() ?: "untitled"
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(messages.first().timestamp))
        val fileName = "$dateStr-$title.md"

        val file = File(getChatDir(context), fileName)

        val sb = StringBuilder()
        // Frontmatter
        sb.appendLine("---")
        sb.appendLine("id: $conversationId")
        sb.appendLine("title: ${firstUserMsg?.content?.take(80) ?: "Untitled"}")
        sb.appendLine("created: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(messages.first().timestamp))}")
        sb.appendLine("model: $model")
        sb.appendLine("---")
        sb.appendLine()

        // Messages
        messages.forEach { msg ->
            when (msg.role) {
                ChatMessage.Role.USER -> {
                    sb.appendLine("## User")
                    sb.appendLine(msg.content)
                    sb.appendLine()
                }
                ChatMessage.Role.ASSISTANT -> {
                    if (!msg.modelName.isNullOrEmpty()) {
                        sb.appendLine("## 🦞 Assistant [${msg.modelName}]")
                    } else {
                        sb.appendLine("## 🦞 Assistant")
                    }
                    sb.appendLine(msg.content)
                    sb.appendLine()
                }
                ChatMessage.Role.SYSTEM -> {
                    sb.appendLine("## System")
                    sb.appendLine(msg.content)
                    sb.appendLine()
                }
                ChatMessage.Role.TOOL_GROUP -> {
                    sb.appendLine("## Tools")
                    msg.toolSteps?.forEach { step ->
                        val icon = if (step.success) "✓" else "○"
                        sb.appendLine("- $icon ${step.toolName} → ${step.summary}")
                    }
                    sb.appendLine()
                }
            }
        }

        file.writeText(sb.toString())

        // Index in SQLite for fast search
        try {
            ChatDatabase(context).indexConversation(
                id = conversationId,
                title = firstUserMsg?.content?.take(80) ?: "Untitled",
                created = messages.first().timestamp,
                model = model,
                filePath = file.absolutePath,
                messages = messages
            )
        } catch (_: Exception) { /* index failure is non-fatal */ }
    }

    /**
     * Load a conversation from markdown file.
     */
    fun load(file: File): List<ChatMessage> {
        if (!file.exists()) return emptyList()

        val messages = mutableListOf<ChatMessage>()
        val lines = file.readLines()

        var inFrontmatter = false
        var currentRole: ChatMessage.Role? = null
        var currentModelName: String? = null
        val contentBuilder = StringBuilder()

        for (line in lines) {
            if (line == "---") {
                if (!inFrontmatter) { inFrontmatter = true; continue }
                else { inFrontmatter = false; continue }
            }
            if (inFrontmatter) continue

            when {
                line.startsWith("## User") -> {
                    flushMessage(messages, currentRole, contentBuilder, currentModelName)
                    currentRole = ChatMessage.Role.USER
                    currentModelName = null
                }
                line.startsWith("## 🦞 Assistant") -> {
                    flushMessage(messages, currentRole, contentBuilder, currentModelName)
                    currentRole = ChatMessage.Role.ASSISTANT
                    // Extract model name from "## 🦞 Assistant [ModelName]"
                    val bracketMatch = Regex("\\[(.+)]").find(line)
                    currentModelName = bracketMatch?.groupValues?.get(1)
                }
                line.startsWith("## System") -> {
                    flushMessage(messages, currentRole, contentBuilder, currentModelName)
                    currentRole = ChatMessage.Role.SYSTEM
                    currentModelName = null
                }
                line.startsWith("## Tools") -> {
                    flushMessage(messages, currentRole, contentBuilder, currentModelName)
                    currentRole = ChatMessage.Role.TOOL_GROUP
                    currentModelName = null
                }
                else -> {
                    if (currentRole != null && line.isNotBlank()) {
                        if (contentBuilder.isNotEmpty()) contentBuilder.appendLine()
                        contentBuilder.append(line)
                    }
                }
            }
        }
        flushMessage(messages, currentRole, contentBuilder, currentModelName)

        return messages
    }

    private fun flushMessage(messages: MutableList<ChatMessage>, role: ChatMessage.Role?, content: StringBuilder, modelName: String? = null) {
        if (role != null && content.isNotEmpty()) {
            messages.add(ChatMessage(role, content.toString().trim(), modelName = modelName))
            content.clear()
        }
    }

    /**
     * List all saved conversations, newest first.
     */
    fun listConversations(context: Context): List<ConversationSummary> {
        val dir = getChatDir(context)
        if (!dir.exists()) return emptyList()

        return dir.listFiles()
            ?.filter { it.extension == "md" }
            ?.map { file ->
                var title = file.nameWithoutExtension
                var id = ""
                // Parse frontmatter for title
                file.useLines { lines ->
                    var inFm = false
                    for (line in lines) {
                        if (line == "---") { if (!inFm) { inFm = true; continue } else break }
                        if (inFm && line.startsWith("title: ")) title = line.removePrefix("title: ")
                        if (inFm && line.startsWith("id: ")) id = line.removePrefix("id: ")
                    }
                }
                ConversationSummary(
                    id = id.ifEmpty { file.nameWithoutExtension },
                    title = title,
                    created = file.lastModified(),
                    file = file
                )
            }
            ?.sortedByDescending { it.created }
            ?: emptyList()
    }

    /**
     * Rename a conversation by updating the title in the markdown frontmatter.
     */
    fun rename(file: File, newTitle: String): Boolean {
        if (!file.exists()) return false
        try {
            val content = file.readText()
            val updated = content.replaceFirst(
                Regex("(?m)^title: .+$"),
                "title: $newTitle"
            )
            file.writeText(updated)
            return true
        } catch (e: Exception) {
            io.agents.pokeclaw.utils.XLog.e("ChatHistoryManager", "Failed to rename conversation", e)
            return false
        }
    }

    /**
     * Delete a conversation.
     */
    fun delete(file: File): Boolean = file.delete()
}
