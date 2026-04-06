package io.agents.pokeclaw.agent.llm

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.model.output.TokenUsage

data class LlmResponse(
    val text: String?,
    val toolExecutionRequests: List<ToolExecutionRequest>,
    val tokenUsage: TokenUsage? = null
) {
    fun hasToolExecutionRequests(): Boolean = toolExecutionRequests.isNotEmpty()
}
