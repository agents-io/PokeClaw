// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.model.output.TokenUsage

data class LlmResponse(
    val text: String?,
    val toolExecutionRequests: List<ToolExecutionRequest>,
    val tokenUsage: TokenUsage? = null,
    /** The actual model name returned by the API (e.g. "gpt-4.1-2025-04-14"). */
    val modelName: String? = null
) {
    fun hasToolExecutionRequests(): Boolean = toolExecutionRequests.isNotEmpty()
}
