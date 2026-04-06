package io.agents.pokeclaw.agent.llm

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ChatMessage

interface LlmClient {
    /** Blocking call. Returns the complete AI response. */
    fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse

    /** Streaming call. Invokes listener callbacks as tokens arrive. Blocks until stream completes. */
    fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener
    ): LlmResponse

    /**
     * Release any engine / native resources held by this client.
     * Called after task completes to free memory before reloading the chat engine.
     * Default is no-op for remote clients (OpenAI, Anthropic).
     */
    fun close() {}
}
