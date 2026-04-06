// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

data class ChatMessage(
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolSteps: List<ToolStep>? = null
) {
    enum class Role { USER, ASSISTANT, SYSTEM, TOOL_GROUP }
}

data class ToolStep(
    val toolName: String,
    val summary: String,
    val success: Boolean = false
)
