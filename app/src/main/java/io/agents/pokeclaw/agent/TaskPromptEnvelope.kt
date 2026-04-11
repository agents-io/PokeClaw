// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

data class ParsedTaskPrompt(
    val currentRequest: String,
    val chatHistory: String? = null,
) {
    val hasChatHistory: Boolean
        get() = !chatHistory.isNullOrBlank()
}

/**
 * Wraps a chatroom transcript and the user's latest request into a stable prompt format.
 *
 * Routing must still use the raw task text. This envelope is only for the agent loop so Cloud
 * tasks can inherit the current chatroom context without breaking deterministic/skill matching.
 */
object TaskPromptEnvelope {

    private const val HISTORY_START = "<<<POKECLAW_CHAT_HISTORY>>>"
    private const val HISTORY_END = "<<<END_POKECLAW_CHAT_HISTORY>>>"
    private const val REQUEST_START = "<<<POKECLAW_CURRENT_REQUEST>>>"
    private const val REQUEST_END = "<<<END_POKECLAW_CURRENT_REQUEST>>>"

    fun build(chatHistoryLines: List<String>, currentRequest: String): String {
        val transcript = chatHistoryLines.joinToString("\n").trim()
        if (transcript.isBlank()) return currentRequest.trim()

        return buildString {
            append(HISTORY_START).append('\n')
            append(transcript).append('\n')
            append(HISTORY_END).append('\n')
            append(REQUEST_START).append('\n')
            append(currentRequest.trim()).append('\n')
            append(REQUEST_END)
        }
    }

    fun parse(prompt: String): ParsedTaskPrompt {
        val historyStart = prompt.indexOf(HISTORY_START)
        val historyEnd = prompt.indexOf(HISTORY_END)
        val requestStart = prompt.indexOf(REQUEST_START)
        val requestEnd = prompt.indexOf(REQUEST_END)

        if (historyStart < 0 || historyEnd < 0 || requestStart < 0 || requestEnd < 0) {
            return ParsedTaskPrompt(currentRequest = prompt.trim())
        }

        val history = prompt.substring(historyStart + HISTORY_START.length, historyEnd).trim()
        val request = prompt.substring(requestStart + REQUEST_START.length, requestEnd).trim()
        return ParsedTaskPrompt(
            currentRequest = request.ifBlank { prompt.trim() },
            chatHistory = history.ifBlank { null },
        )
    }
}
