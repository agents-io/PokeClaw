// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.agent.AgentConfig
import io.agents.pokeclaw.utils.XLog
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import io.agents.pokeclaw.tool.ToolRegistry
import io.agents.pokeclaw.tool.ToolResult
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * LlmClient implementation using Google LiteRT-LM SDK for on-device inference.
 *
 * Bridges the stateless LangChain4j chat interface (full message list per call)
 * to LiteRT-LM's stateful Conversation API (incremental messages).
 *
 * config.baseUrl is repurposed to hold the local model file path.
 */
class LocalLlmClient(private val config: AgentConfig) : LlmClient {

    private val GSON = Gson()

    private data class ToolParseResult(
        val requests: List<ToolExecutionRequest>,
        val hasToolCalls: Boolean,
    )

    // Engine is owned by the shared local runtime.
    // We keep a local reference only for null-check convenience.
    private var engine: Engine? = null
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    private var processedMessageCount = 0
    private var activeBackendLabel: String = "unknown"

    private fun ensureEngine() {
        val modelPath = config.baseUrl
        val context = ClawApplication.instance
        try {
            val shared = LocalModelRuntime.acquireSharedEngine(
                context = context,
                modelPath = modelPath,
                preferCpu = false,
            ).engine
            if (engine !== shared) {
                XLog.i(TAG, "ensureEngine: obtained shared engine for $modelPath")
                engine = shared
            }
            activeBackendLabel = LocalModelRuntime.currentBackendLabel(modelPath) ?: "unknown"
            XLog.d(TAG, "ensureEngine: backend=$activeBackendLabel")
        } catch (e: Exception) {
            XLog.e(TAG, "ensureEngine: failed to get engine from shared runtime", e)
            throw e
        }
    }

    /**
     * Create a new conversation with system prompt and tool declarations.
     */
    private fun createConversation(systemPrompt: String, toolSpecs: List<ToolSpecification>) {
        // LiteRT-LM only supports one session at a time — close existing first
        try { conversation?.close() } catch (_: Exception) {}
        conversation = null

        val promptWithManualTools = buildManualToolPrompt(systemPrompt, toolSpecs)
        XLog.i(TAG, "createConversation: sdk tools hidden, manual tool schemas injected (${toolSpecs.size})")

        val convConfig = ConversationConfig(
            systemInstruction = Contents.of(promptWithManualTools),
            tools = emptyList(),
            samplerConfig = SamplerConfig(
                topK = 64,
                topP = 0.95,
                temperature = config.temperature,
            ),
            automaticToolCalling = false
        )

        val lease = LocalModelRuntime.openConversation(
            context = ClawApplication.instance,
            modelPath = config.baseUrl,
            conversationConfig = convConfig,
            preferCpu = false,
        )
        engine = lease.engine
        conversation = lease.conversation
        activeBackendLabel = lease.backendLabel
        XLog.i(TAG, "createConversation: backend=$activeBackendLabel model=${config.baseUrl}")
        processedMessageCount = 0
    }

    private fun buildManualToolPrompt(basePrompt: String, toolSpecs: List<ToolSpecification>): String {
        if (toolSpecs.isEmpty()) return basePrompt
        val toolSchemas = toolSpecs.mapNotNull { spec ->
            try {
                DynamicOpenApiTool(spec).getToolDescriptionJsonString()
            } catch (e: Exception) {
                XLog.w(TAG, "buildManualToolPrompt: failed to serialize schema for ${spec.name()}", e)
                null
            }
        }
        if (toolSchemas.isEmpty()) return basePrompt

        return buildString {
            append(basePrompt.trimEnd())
            append("\n\n## TOOL CALLING RULES (MANUAL MODE)\n")
            append("- DO NOT use native Gemma tool tokens (No <|tool_call|>).\n")
            append("- To execute a tool, you MUST output exactly this plain-text format:\n")
            append("Action: tool_name\n")
            append("{\"arg1\": \"value1\"}\n")
            append("- The arguments MUST be a valid JSON object. Always use standard double quotes.\n")
            append("\n## Available Tools (JSON Schema)\n")
            toolSchemas.forEach { schema ->
                append(schema)
                append('\n')
            }
        }
    }

    private var sendCount = 0

    override fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
        return try {
            runBlocking {
                chatInternal(messages, toolSpecs)
            }
        } catch (e: Exception) {
            XLog.e(TAG, "chat: local model execution failed", e)
            throw e
        }
    }

    private suspend fun chatInternal(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
        currentCoroutineContext().ensureActive()
        ensureEngine()

        // Detect new task or recreate needed
        if (processedMessageCount == 0 || messages.size < processedMessageCount || sendCount >= 8) {
            val systemPrompt = messages.filterIsInstance<SystemMessage>().firstOrNull()?.text()
                ?: config.systemPrompt.ifEmpty { LOCAL_SYSTEM_PROMPT }
            createConversation(systemPrompt, toolSpecs)
            sendCount = 0
            processedMessageCount = 0
        }

        // Find new messages to send
        val newMessages = messages.subList(
            processedMessageCount.coerceAtMost(messages.size),
            messages.size
        )

        var lastResponse: Any? = null

        for (msg in newMessages) {
            currentCoroutineContext().ensureActive()
            when (msg) {
                is SystemMessage -> { /* handled in createConversation */ }
                is UserMessage -> {
                    val conv = conversation ?: throw RuntimeException("LiteRT-LM conversation not initialized — engine may have failed to load the model")
                    XLog.i(TAG, "chat: roundSend type=user backend=$activeBackendLabel sendCount=$sendCount")
                    XLog.d(TAG, "chat: sendMessage user (${msg.singleText().take(80)}...) sendCount=$sendCount")
                    lastResponse = withContext(Dispatchers.Default) {
                        currentCoroutineContext().ensureActive()
                        conv.sendMessage(msg.singleText())
                    }
                    sendCount++
                }
                is AiMessage -> { /* already in conversation state */ }
                is ToolExecutionResultMessage -> {
                    // Truncate tool results to prevent token overflow + reduce crash risk
                    val truncatedResult = msg.text().take(400)
                    val toolResultText = "[Tool ${msg.toolName()} result]: $truncatedResult"
                    val conv = conversation ?: throw RuntimeException("LiteRT-LM conversation not initialized — engine may have failed to load the model")
                    XLog.i(TAG, "chat: roundSend type=tool_result backend=$activeBackendLabel sendCount=$sendCount tool=${msg.toolName()}")
                    XLog.d(TAG, "chat: sendMessage toolResult (${toolResultText.take(80)}...) sendCount=$sendCount")
                    lastResponse = withContext(Dispatchers.Default) {
                        currentCoroutineContext().ensureActive()
                        conv.sendMessage(toolResultText)
                    }
                    sendCount++
                }
            }
        }

        processedMessageCount = messages.size
        return parseResponse(lastResponse)
    }

    override fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener
    ): LlmResponse {
        // For now, delegate to blocking chat and simulate streaming
        // LiteRT-LM streaming requires Flow or MessageCallback which needs more integration
        val response = runBlocking { chat(messages, toolSpecs) }
        if (!response.text.isNullOrEmpty()) {
            listener.onPartialText(response.text)
        }
        listener.onComplete(response)
        return response
    }

    /** Parse LiteRT-LM response into LlmResponse using raw-string tool call parsing. */
    private fun parseResponse(response: Any?): LlmResponse {
        if (response is Message) {
            val rawText = response.contents.toString().trim()
            val parseResult = extractToolCalls(rawText)
            if (parseResult.hasToolCalls) {
                val thinkingText = rawText
                    .replace(ACTION_TOOL_PATTERN, "")
                    .trim()
                    .ifEmpty { null }
                return LlmResponse(text = thinkingText, toolExecutionRequests = parseResult.requests)
            }
            return LlmResponse(text = rawText.ifEmpty { null }, toolExecutionRequests = emptyList())
        }

        return LlmResponse(
            text = response?.toString()?.trim()?.ifEmpty { null },
            toolExecutionRequests = emptyList()
        )
    }

    private fun extractToolCalls(text: String): ToolParseResult {
        val calls = mutableListOf<ToolExecutionRequest>()
        var hasToolCalls = false

        ACTION_TOOL_PATTERN.findAll(text).forEach { match ->
            hasToolCalls = true
            val name = match.groupValues[1].trim()
            val argsJson = match.groupValues[2].trim()
            try {
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
                val map: Map<String, Any?> = GSON.fromJson(argsJson, mapType) ?: emptyMap()
                calls.add(
                    ToolExecutionRequest.builder()
                        .id("local_${System.currentTimeMillis()}")
                        .name(name)
                        .arguments(argsJson)
                        .build()
                )
            } catch (e: Exception) {
                XLog.w(TAG, "Failed to parse tool JSON: $argsJson", e)
            }
        }

        return ToolParseResult(requests = calls, hasToolCalls = hasToolCalls)
    }

    override fun close() {
        XLog.i(TAG, "close() — closing conversation only (engine stays in EngineHolder)")
        try { conversation?.close() } catch (e: Exception) { XLog.w(TAG, "close conversation error", e) }
        conversation = null
        engine = null
        processedMessageCount = 0
        XLog.i(TAG, "close() — done")
    }

    companion object {
        private const val TAG = "LocalLlmClient"

        private val ACTION_TOOL_PATTERN = Regex(
            pattern = """Action:\s*([a-zA-Z0-9_]+)\s*(\{.*?\})""",
            options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        private const val LOCAL_SYSTEM_PROMPT = """You control an Android phone via tools. Screen shows elements as: [n1] "text" [flags] (x,y) where n1 is the node ID and (x,y) is the tap target.

TOOL CALLING RULES:
- Use open_app(app_name) to open apps, e.g. open_app("Camera"), open_app("WhatsApp")
- Use tap_node(node_id) to tap elements by node ID (preferred), e.g. tap_node("n3")
- Use tap(x,y) only when you know exact coordinates and no node ID is available
- Use input_text(text) to type into focused editable fields
- Use system_key(key) with key="back","home","enter" for navigation
- Use finish(summary) when task is complete
- One tool per turn. Read screen after each action.
- To message someone: use send_message(contact="Name", message="text", app="WhatsApp"). This handles everything automatically.
- CRITICAL: You must use the EXACT contact names and strings provided in the user prompt. Do not shorten, summarize, or simplify names (e.g., if the user says "Kamya Gupta Personal", do not use "Kamya Gupta").
- DO NOT use native Gemma tool tokens (No <|tool_call|>).
- To execute a tool, you MUST output exactly this plain-text format:
- Action: tool_name
- {"arg1": "value1"}
- The arguments MUST be a valid JSON object. Always use standard double quotes.
- Do NOT try to navigate messaging apps manually — always use send_message tool instead."""
    }
}

/**
 * Wraps a LangChain4j ToolSpecification as a LiteRT-LM OpenApiTool.
 * Execution is manual: the model emits raw tool call text, and LocalLlmClient
 * parses and routes it to ToolRegistry outside of the native parser path.
 */
private class DynamicOpenApiTool(private val spec: ToolSpecification) : OpenApiTool {

    private val gson = Gson()

    override fun getToolDescriptionJsonString(): String {
        val json = buildMap {
            put("name", spec.name())
            put("description", spec.description() ?: "")
            spec.parameters()?.let { params ->
                put("parameters", schemaToMap(params))
            }
        }
        return gson.toJson(json)
    }

    override fun execute(paramsJsonString: String): String {
        return try {
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            val parsed: Map<String, Any?> = gson.fromJson(paramsJsonString, mapType) ?: emptyMap()
            val params = LinkedHashMap<String, Any>()
            parsed.forEach { (key, value) ->
                if (value != null) params[key] = value
            }
            val result = ToolRegistry.getInstance().executeTool(spec.name(), params)
            gson.toJson(result)
        } catch (e: Exception) {
            gson.toJson(ToolResult.error("Tool execution failed in native bridge: ${e.message}"))
        }
    }

    private fun schemaToMap(schema: Any?): Map<String, Any> {
        val output = mutableMapOf<String, Any>()
        if (schema == null) {
            output["type"] = "string"
            return output
        }

        val type = when (schema.javaClass.simpleName) {
            "JsonIntegerSchema" -> "integer"
            "JsonNumberSchema" -> "number"
            "JsonBooleanSchema" -> "boolean"
            "JsonObjectSchema" -> "object"
            "JsonArraySchema" -> "array"
            else -> "string"
        }
        output["type"] = type

        invokeMethod(schema, "description")?.toString()?.takeIf { it.isNotBlank() }?.let {
            output["description"] = it
        }

        if (type == "object") {
            val rawProps = invokeMethod(schema, "properties") as? Map<*, *>
            if (!rawProps.isNullOrEmpty()) {
                val mapped = LinkedHashMap<String, Any>()
                rawProps.forEach { (key, value) ->
                    if (key != null) mapped[key.toString()] = schemaToMap(value)
                }
                output["properties"] = mapped
            }

            val required = (invokeMethod(schema, "required") as? Collection<*>)
                ?.mapNotNull { it?.toString() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            if (required.isNotEmpty()) {
                output["required"] = required
            }
        }

        if (type == "array") {
            val items = invokeMethod(schema, "items")
                ?: invokeMethod(schema, "item")
                ?: invokeMethod(schema, "element")
            if (items != null) {
                output["items"] = schemaToMap(items)
            }
        }

        return output
    }

    private fun invokeMethod(target: Any, methodName: String): Any? {
        return try {
            target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
                ?.invoke(target)
        } catch (_: Exception) {
            null
        }
    }
}
