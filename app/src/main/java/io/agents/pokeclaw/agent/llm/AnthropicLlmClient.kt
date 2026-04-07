// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import io.agents.pokeclaw.agent.AgentConfig
import io.agents.pokeclaw.agent.langchain.http.OkHttpClientBuilderAdapter
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AnthropicLlmClient(
    private val config: AgentConfig,
    private val httpClientBuilder: OkHttpClientBuilderAdapter
) : LlmClient {

    private val chatModel: ChatModel by lazy { buildChatModel() }
    private val streamingChatModel: StreamingChatModel by lazy { buildStreamingChatModel() }

    private fun buildChatModel(): ChatModel {
        val builder = AnthropicChatModel.builder()
            .httpClientBuilder(httpClientBuilder)
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .temperature(config.temperature)
        if (config.baseUrl.isNotEmpty()) {
            builder.baseUrl(config.baseUrl)
        }
        return builder.build()
    }

    private fun buildStreamingChatModel(): StreamingChatModel {
        val builder = AnthropicStreamingChatModel.builder()
            .httpClientBuilder(httpClientBuilder)
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .temperature(config.temperature)
        if (config.baseUrl.isNotEmpty()) {
            builder.baseUrl(config.baseUrl)
        }
        return builder.build()
    }

    override fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
        val request = ChatRequest.builder()
            .messages(messages)
            .toolSpecifications(toolSpecs)
            .build()
        val response = chatModel.chat(request)
        return response.toLlmResponse()
    }

    override fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener
    ): LlmResponse {
        val request = ChatRequest.builder()
            .messages(messages)
            .toolSpecifications(toolSpecs)
            .build()

        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<LlmResponse>()
        val errorRef = AtomicReference<Throwable>()

        streamingChatModel.chat(request, object : StreamingChatResponseHandler {
            override fun onPartialResponse(token: String) {
                listener.onPartialText(token)
            }

            override fun onCompleteResponse(response: ChatResponse) {
                val llmResponse = response.toLlmResponse()
                resultRef.set(llmResponse)
                listener.onComplete(llmResponse)
                latch.countDown()
            }

            override fun onError(error: Throwable) {
                errorRef.set(error)
                listener.onError(error)
                latch.countDown()
            }
        })

        if (!latch.await(120, TimeUnit.SECONDS)) {
            throw RuntimeException("Anthropic streaming response timed out after 120 seconds")
        }
        errorRef.get()?.let { throw RuntimeException("Anthropic streaming error", it) }
        return resultRef.get()
    }
}
