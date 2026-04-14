// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.agent.AgentConfig
import io.agents.pokeclaw.utils.XLog
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import com.google.gson.Gson
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

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

    // Engine is owned by the shared local runtime.
    // We keep a local reference only for null-check convenience.
    private var engine: Engine? = null
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    private var processedMessageCount = 0

    private var gpuFailed = false

    private fun noteGpuInferenceSuccessIfNeeded() {
        if (EngineHolder.getBackendLabel(config.baseUrl) == "GPU") {
            LocalBackendHealth.noteGpuInferenceSuccess(config.baseUrl)
        }
    }

    private fun ensureEngine() {
        val modelPath = config.baseUrl
        val context = ClawApplication.instance
        try {
            val shared = LocalModelRuntime.acquireSharedEngine(
                context = context,
                modelPath = modelPath,
                preferCpu = gpuFailed,
            ).engine
            if (engine !== shared) {
                XLog.i(TAG, "ensureEngine: obtained shared engine for $modelPath")
                engine = shared
            }
        } catch (e: Exception) {
            if (gpuFailed || !LocalModelRuntime.isGpuBackendFailure(e)) {
                XLog.e(TAG, "ensureEngine: failed to get engine from shared runtime", e)
                throw e
            }

            XLog.w(TAG, "ensureEngine: GPU engine init failed, retrying on CPU: ${e.message}")
            gpuFailed = true
            val cpuShared = LocalModelRuntime.forceCpuEngine(context, modelPath).engine
            if (engine !== cpuShared) {
                XLog.i(TAG, "ensureEngine: obtained shared CPU engine for $modelPath")
                engine = cpuShared
            }
        }
    }

    /**
     * Force engine to recreate with CPU backend. Called when GPU inference fails
     * (e.g. OpenCL library not found).
     */
    private fun fallbackToCpu() {
        XLog.w(TAG, "fallbackToCpu: GPU inference failed, switching to CPU")
        gpuFailed = true
        try { conversation?.close() } catch (_: Exception) {}
        conversation = null
        processedMessageCount = 0
        sendCount = 0
        engine = LocalModelRuntime.forceCpuEngine(ClawApplication.instance, config.baseUrl).engine
    }

    /**
     * Create a new conversation with system prompt and tool declarations.
     */
    private fun createConversation(systemPrompt: String, toolSpecs: List<ToolSpecification>) {
        // LiteRT-LM only supports one session at a time — close existing first
        try { conversation?.close() } catch (_: Exception) {}
        conversation = null

        // Convert tool specs to native LiteRT-LM tools
        val nativeTools = toolSpecs.mapNotNull { spec ->
            try {
                val paramsJson = try { GSON.toJson(spec.parameters()) } catch (_: Exception) { "{}" }
                com.google.ai.edge.litertlm.tool(object : com.google.ai.edge.litertlm.OpenApiTool {
                    override fun getToolDescriptionJsonString(): String = GSON.toJson(mapOf(
                        "name" to spec.name(),
                        "description" to (spec.description() ?: ""),
                        "parameters" to try { GSON.fromJson(paramsJson, Any::class.java) } catch (_: Exception) { emptyMap<String, Any>() }
                    ))
                    override fun execute(params: String): String = "{}" // Execution handled by DefaultAgentService
                })
            } catch (e: Exception) {
                XLog.w(TAG, "Failed to wrap tool: ${spec.name()}", e)
                null
            }
        }

        XLog.i(TAG, "createConversation: ${nativeTools.size} native tools")

        val convConfig = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            tools = nativeTools,
            samplerConfig = SamplerConfig(
                topK = 64,
                topP = 0.95,
                temperature = config.temperature
            ),
            automaticToolCalling = false  // We handle execution in DefaultAgentService
        )

        val lease = LocalModelRuntime.openConversation(
            context = ClawApplication.instance,
            modelPath = config.baseUrl,
            conversationConfig = convConfig,
            preferCpu = gpuFailed,
        )
        engine = lease.engine
        conversation = lease.conversation
        processedMessageCount = 0
    }

    private var sendCount = 0

    override fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
        return try {
            chatInternal(messages, toolSpecs)
        } catch (e: Exception) {
            // GPU inference failure (OpenCL not found) — fallback to CPU and retry once
            if (!gpuFailed && LocalModelRuntime.isGpuBackendFailure(e)) {
                XLog.w(TAG, "chat: GPU inference failed, retrying with CPU: ${e.message}")
                fallbackToCpu()
                chatInternal(messages, toolSpecs)
            } else {
                throw e
            }
        }
    }

    private fun chatInternal(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
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
            when (msg) {
                is SystemMessage -> { /* handled in createConversation */ }
                is UserMessage -> {
                    val conv = conversation ?: throw RuntimeException("LiteRT-LM conversation not initialized — engine may have failed to load the model")
                    XLog.d(TAG, "chat: sendMessage user (${msg.singleText().take(80)}...) sendCount=$sendCount")
                    try {
                        lastResponse = conv.sendMessage(msg.singleText())
                        noteGpuInferenceSuccessIfNeeded()
                    } catch (e: Exception) {
                        // LiteRT-LM SDK may fail to parse tool calls with standard quotes.
                        // Extract raw model output from error message and parse ourselves.
                        val errorMsg = e.message ?: ""
                        if (errorMsg.contains("Failed to parse tool calls") && errorMsg.contains("tool_call")) {
                            XLog.w(TAG, "SDK tool call parse failed, extracting from error: ${errorMsg.take(200)}")
                            // Extract the raw output between "from response: " and the next error description
                            val rawOutput = errorMsg.substringAfter("from response: ").substringBefore("code block:")
                                .ifEmpty { errorMsg.substringAfter("from response: ") }
                            lastResponse = rawOutput.trim()
                            noteGpuInferenceSuccessIfNeeded()
                        } else {
                            throw e
                        }
                    }
                    sendCount++
                }
                is AiMessage -> { /* already in conversation state */ }
                is ToolExecutionResultMessage -> {
                    // Truncate tool results to prevent token overflow + reduce crash risk
                    val truncatedResult = msg.text().take(400)
                    val toolResultText = "[Tool ${msg.toolName()} result]: $truncatedResult"
                    val conv = conversation ?: throw RuntimeException("LiteRT-LM conversation not initialized — engine may have failed to load the model")
                    XLog.d(TAG, "chat: sendMessage toolResult (${toolResultText.take(80)}...) sendCount=$sendCount")
                    try {
                        lastResponse = conv.sendMessage(toolResultText)
                        noteGpuInferenceSuccessIfNeeded()
                    } catch (e: Exception) {
                        val errorMsg = e.message ?: ""
                        if (errorMsg.contains("Failed to parse tool calls") && errorMsg.contains("tool_call")) {
                            XLog.w(TAG, "SDK tool call parse failed on toolResult, extracting: ${errorMsg.take(200)}")
                            val rawOutput = errorMsg.substringAfter("from response: ").substringBefore("code block:")
                                .ifEmpty { errorMsg.substringAfter("from response: ") }
                            lastResponse = rawOutput.trim()
                            noteGpuInferenceSuccessIfNeeded()
                        } else {
                            throw e
                        }
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
        val response = chat(messages, toolSpecs)
        if (!response.text.isNullOrEmpty()) {
            listener.onPartialText(response.text)
        }
        listener.onComplete(response)
        return response
    }

    /**
     * Parse LiteRT-LM response into LlmResponse.
     *
     * The response text may contain tool calls in Gemma's function calling format:
     * <tool_call>{"name": "tap", "arguments": {"x": 100, "y": 200}}</tool_call>
     *
     * Or it may be plain text (thinking + final answer).
     */
    private fun parseResponse(response: Any?): LlmResponse {
        // Check for native LiteRT-LM Message with structured tool calls
        if (response is com.google.ai.edge.litertlm.Message) {
            val nativeToolCalls = response.toolCalls
            if (!nativeToolCalls.isNullOrEmpty()) {
                val converted = nativeToolCalls.mapNotNull { tc ->
                    try {
                        ToolExecutionRequest.builder()
                            .id("native_${System.currentTimeMillis()}")
                            .name(tc.name)
                            .arguments(GSON.toJson(tc.arguments))
                            .build()
                    } catch (e: Exception) {
                        XLog.w(TAG, "Failed to convert native ToolCall: ${tc.name}", e)
                        null
                    }
                }
                if (converted.isNotEmpty()) {
                    XLog.i(TAG, "parseResponse: ${converted.size} native tool calls from SDK")
                    val text = response.contents?.toString()?.trim()?.ifEmpty { null }
                    return LlmResponse(text = text, toolExecutionRequests = converted)
                }
            }
        }

        val responseText = response?.toString() ?: ""

        // Fallback: extract tool calls from text (for prompt-based tool calling)
        val toolCalls = extractToolCalls(responseText)

        if (toolCalls.isNotEmpty()) {
            // Remove all tool call markup from text to extract the thinking portion
            val thinkingText = responseText
                .replace(TOOL_CALL_PATTERN, "")
                .replace(GEMMA4_NATIVE_PATTERN, "")
                .replace(TOOL_CALL_BLOCK_PATTERN, "")
                .trim()
                .ifEmpty { null }

            return LlmResponse(
                text = thinkingText,
                toolExecutionRequests = toolCalls
            )
        }

        return LlmResponse(
            text = responseText,
            toolExecutionRequests = emptyList()
        )
    }

    /**
     * Extract tool calls from model output.
     *
     * Gemma 4 uses special tokens for function calling. The format may be:
     * - <tool_call>{"name":"tap","arguments":{"x":100,"y":200}}</tool_call>
     * - ```tool_call\n{"name":"tap","arguments":{"x":100,"y":200}}\n```
     * - Or JSON objects with "name" and "arguments" fields
     *
     * This parser tries multiple patterns.
     */
    private fun extractToolCalls(text: String): List<ToolExecutionRequest> {
        val calls = mutableListOf<ToolExecutionRequest>()

        // Pattern 1: Standard <tool_call>{"name":...,"arguments":{...}}</tool_call>
        // Also handles: <tool_call>tool_name{"key":"value",...}</tool_call>
        TOOL_CALL_PATTERN.findAll(text).forEach { match ->
            val content = match.groupValues[1].trim()
            if (content.startsWith("{")) {
                // Standard JSON format
                parseToolCallJson(content)?.let { calls.add(it) }
            } else {
                // tool_name{...} format — extract name and treat rest as arguments
                val nameEnd = content.indexOf('{')
                if (nameEnd > 0) {
                    val name = content.substring(0, nameEnd).trim()
                    val argsJson = content.substring(nameEnd)
                    // Parse the JSON as arguments directly
                    try {
                        var fixed = argsJson
                        val open = fixed.count { it == '{' }
                        val close = fixed.count { it == '}' }
                        repeat(open - close) { fixed += "}" }
                        val args = GSON.fromJson(fixed, Map::class.java) as Map<*, *>
                        val argsStr = GSON.toJson(args)
                        XLog.d(TAG, "extractToolCalls: parsed name=$name args=$argsStr from tool_name{} format")
                        calls.add(ToolExecutionRequest.builder()
                            .id("local_${System.currentTimeMillis()}")
                            .name(name)
                            .arguments(argsStr)
                            .build())
                    } catch (e: Exception) {
                        XLog.w(TAG, "extractToolCalls: failed to parse tool_name{} format: $content", e)
                    }
                }
            }
        }
        if (calls.isNotEmpty()) {
            XLog.d(TAG, "extractToolCalls: matched ${calls.size} calls via TOOL_CALL_PATTERN")
            return calls
        }

        // Pattern 2: Gemma 4 native token format <|tool_call>call:name{key:<|"|>value<|"|>}<tool_call|>
        // Gemma 4 E2B may emit its built-in token format instead of plain JSON tags
        GEMMA4_NATIVE_PATTERN.findAll(text).forEach { match ->
            parseGemma4NativeCall(match.groupValues[1])?.let { calls.add(it) }
        }
        if (calls.isNotEmpty()) {
            XLog.d(TAG, "extractToolCalls: matched ${calls.size} calls via GEMMA4_NATIVE_PATTERN")
            return calls
        }

        // Pattern 2b: Gemma 4 native WITHOUT closing tag: <|tool_call>call:name(...)
        val gemmaNoClose = Regex("""<\|tool_call>(call:\w+[\(\{].*)""")
        gemmaNoClose.findAll(text).forEach { match ->
            parseGemma4NativeCall(match.groupValues[1].trim())?.let { calls.add(it) }
        }
        if (calls.isNotEmpty()) {
            XLog.d(TAG, "extractToolCalls: matched ${calls.size} calls via GEMMA4_NO_CLOSE")
            return calls
        }

        // Pattern 3: ```tool_call\n...\n``` fenced blocks
        TOOL_CALL_BLOCK_PATTERN.findAll(text).forEach { match ->
            parseToolCallJson(match.groupValues[1])?.let { calls.add(it) }
        }
        if (calls.isNotEmpty()) {
            XLog.d(TAG, "extractToolCalls: matched ${calls.size} calls via TOOL_CALL_BLOCK_PATTERN")
            return calls
        }

        // Pattern 4: Legacy functioncall/function_call prefix format
        // e.g. functioncall: {"name": "tap", "args": {"x": 100, "y": 200}}
        FUNCTION_CALL_PATTERN.findAll(text).forEach { match ->
            parseToolCallJson(match.groupValues[1], argsKey = "args")?.let { calls.add(it) }
        }
        if (calls.isNotEmpty()) {
            XLog.d(TAG, "extractToolCalls: matched ${calls.size} calls via FUNCTION_CALL_PATTERN")
        }

        return calls
    }

    /**
     * Parse Gemma 4's native token format into a ToolExecutionRequest.
     *
     * Gemma 4 emits: call:tool_name{key:<|"|>value<|"|>,key2:<|"|>value2<|"|>}
     * The <|"|> tokens are Gemma's quote markers. We strip them and reconstruct JSON.
     *
     * Example input: "call:tap{x:<|"|>540<|"|>,y:<|"|>960<|"|>}"
     * Parsed as: name="tap", arguments={"x":"540","y":"960"}
     */
    private fun parseGemma4NativeCall(rawContent: String): ToolExecutionRequest? {
        return try {
            val content = rawContent.trim()
            XLog.d(TAG, "parseGemma4NativeCall: raw=$content")

            // Extract name and params — supports both call:name{...} and call:name("...")
            val nameMatch = Regex("""^call:(\w+)[\(\{]""").find(content) ?: run {
                return parseToolCallJson(content)
            }
            val name = nameMatch.groupValues[1]

            // Extract params — could be {key:value} or ("value") or (key=value)
            val openChar = content[nameMatch.range.last]
            val closeChar = if (openChar == '{') '}' else ')'
            val paramsStart = content.indexOf(openChar)
            val paramsEnd = content.lastIndexOf(closeChar)
            if (paramsStart < 0 || paramsEnd <= paramsStart) return null
            val paramsRaw = content.substring(paramsStart + 1, paramsEnd)

            // If simple string arg like ("WhatsApp"), convert to first param of tool
            if (openChar == '(' && !paramsRaw.contains(':') && !paramsRaw.contains('=')) {
                val cleanVal = paramsRaw.trim().removeSurrounding("\"").removeSurrounding("<|\"", "\"|>")
                val argsJson = GSON.toJson(mapOf("app_name" to cleanVal, "package_name" to cleanVal, "text" to cleanVal, "key" to cleanVal, "summary" to cleanVal))
                XLog.d(TAG, "parseGemma4NativeCall: name=$name simpleArg=$cleanVal args=$argsJson")
                return ToolExecutionRequest.builder()
                    .id("local_${System.currentTimeMillis()}")
                    .name(name)
                    .arguments(argsJson)
                    .build()
            }

            // Parse key-value pairs from multiple possible formats
            val argsMap = mutableMapOf<String, String>()

            // Format 1: key:<|"|>value<|"|> (Gemma native tokens)
            val gemmaKv = Regex("""(\w+):<\|"\|>(.*?)<\|"\|>""")
            gemmaKv.findAll(paramsRaw).forEach { kv ->
                argsMap[kv.groupValues[1]] = kv.groupValues[2]
            }
            // Format 2: key="value" or key:"value" (equals or colon with quotes)
            val quotedKv = Regex("""(\w+)[=:]"([^"]*?)"""")
            quotedKv.findAll(paramsRaw).forEach { kv ->
                val key = kv.groupValues[1]
                if (!argsMap.containsKey(key)) {
                    argsMap[key] = kv.groupValues[2]
                }
            }
            // Format 3: key:value (bare numeric/boolean)
            val bareKv = Regex("""(\w+):([^,<}"=\s]+)""")
            bareKv.findAll(paramsRaw).forEach { kv ->
                val key = kv.groupValues[1]
                if (!argsMap.containsKey(key)) {
                    argsMap[key] = kv.groupValues[2]
                }
            }

            val argsJson = GSON.toJson(argsMap)
            XLog.d(TAG, "parseGemma4NativeCall: name=$name args=$argsJson")

            ToolExecutionRequest.builder()
                .id("local_${System.currentTimeMillis()}")
                .name(name)
                .arguments(argsJson)
                .build()
        } catch (e: Exception) {
            XLog.w(TAG, "parseGemma4NativeCall failed: $rawContent", e)
            null
        }
    }

    private fun parseToolCallJson(json: String, argsKey: String = "arguments"): ToolExecutionRequest? {
        return try {
            val trimmed = json.trim()
            // Handle multiple tool calls separated by commas: {...},{...}
            // Take only the FIRST one (one tool per turn rule)
            // We need to find the matching closing brace for the first object
            val firstJson = if (trimmed.startsWith("{") && trimmed.contains("},{")) {
                // Find balanced braces for first JSON object
                var depth = 0
                var endIdx = 0
                for (i in trimmed.indices) {
                    when (trimmed[i]) {
                        '{' -> depth++
                        '}' -> { depth--; if (depth == 0) { endIdx = i; break } }
                    }
                }
                trimmed.substring(0, endIdx + 1)
            } else {
                trimmed
            }

            // Fix malformed JSON from LLM
            var fixedJson = firstJson
            // Auto-close missing braces
            val openBraces = fixedJson.count { it == '{' }
            val closeBraces = fixedJson.count { it == '}' }
            repeat(openBraces - closeBraces) { fixedJson += "}" }

            val map = try {
                GSON.fromJson(fixedJson, Map::class.java) as Map<*, *>
            } catch (e: Exception) {
                // Fallback: extract name and arguments with regex
                XLog.w(TAG, "JSON parse failed, trying regex fallback: $fixedJson")
                val nameRegex = Regex(""""name"\s*:\s*"(\w+)"""")
                val argsRegex = Regex(""""arguments"\s*:\s*\{([^}]*)\}""")
                val n = nameRegex.find(fixedJson)?.groupValues?.get(1) ?: return null
                val argsRaw = argsRegex.find(fixedJson)?.groupValues?.get(1) ?: ""
                // Parse key-value pairs from arguments
                val argsMap = mutableMapOf<String, Any>()
                Regex(""""(\w+)"\s*:\s*"([^"]*?)"""").findAll(argsRaw).forEach {
                    argsMap[it.groupValues[1]] = it.groupValues[2]
                }
                mapOf("name" to n, "arguments" to argsMap)
            }
            val name = map["name"]?.toString() ?: return null
            val args = map[argsKey]
            val argsJson = if (args is Map<*, *>) GSON.toJson(args) else args?.toString() ?: "{}"

            ToolExecutionRequest.builder()
                .id("local_${System.currentTimeMillis()}")
                .name(name)
                .arguments(argsJson)
                .build()
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to parse tool call JSON: $json", e)
            null
        }
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

        private const val LOCAL_SYSTEM_PROMPT = """You control an Android phone via tools. Screen shows elements as: [n1] "text" [flags] (x,y) where n1 is the node ID and (x,y) is the tap target.

Rules:
- Use open_app(app_name) to open apps, e.g. open_app("Camera"), open_app("WhatsApp")
- Use tap_node(node_id) to tap elements by node ID (preferred), e.g. tap_node("n3")
- Use tap(x,y) only when you know exact coordinates and no node ID is available
- Use input_text(text) to type into focused editable fields
- Use system_key(key) with key="back","home","enter" for navigation
- Use finish(summary) when task is complete
- One tool per turn. Read screen after each action.
- To message someone: use send_message(contact="Name", message="text", app="WhatsApp"). This handles everything automatically.
- Do NOT try to navigate messaging apps manually — always use send_message tool instead."""

        // Pattern 1: Standard <tool_call>...</tool_call> tags (preferred format)
        private val TOOL_CALL_PATTERN = Regex("""<tool_call>(.*?)</tool_call>""", RegexOption.DOT_MATCHES_ALL)

        // Pattern 2: Gemma 4 native trained token format: <|tool_call>call:name{key:<|"|>value<|"|>}<tool_call|>
        // This is the format Gemma 4 E2B emits when using its built-in function calling tokens
        private val GEMMA4_NATIVE_PATTERN = Regex("""<\|tool_call>(.*?)<tool_call\|>""", RegexOption.DOT_MATCHES_ALL)

        // Pattern 3: Fenced code block format
        private val TOOL_CALL_BLOCK_PATTERN = Regex("""```tool_call\s*\n(.*?)\n\s*```""", RegexOption.DOT_MATCHES_ALL)

        // Pattern 4: Legacy functioncall/function_call prefix format
        private val FUNCTION_CALL_PATTERN = Regex("""(?:functioncall|function_call|tool_call)\s*:\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL)
    }
}

/**
 * Wraps a LangChain4j ToolSpecification as a LiteRT-LM OpenApiTool.
 * Only declares the schema — execution is handled by the agent loop.
 */
private class DynamicOpenApiTool(private val spec: ToolSpecification) : OpenApiTool {

    override fun getToolDescriptionJsonString(): String {
        val json = buildMap {
            put("name", spec.name())
            put("description", spec.description() ?: "")
            spec.parameters()?.let { params ->
                put("parameters", buildMap {
                    put("type", "object")
                    val properties = mutableMapOf<String, Any>()
                    val required = mutableListOf<String>()

                    // Extract properties from JsonObjectSchema
                    params.properties()?.forEach { (name, schema) ->
                        val prop = mutableMapOf<String, Any>()
                        prop["description"] = schema.description() ?: ""
                        prop["type"] = when (schema.javaClass.simpleName) {
                            "JsonIntegerSchema" -> "integer"
                            "JsonNumberSchema" -> "number"
                            "JsonBooleanSchema" -> "boolean"
                            else -> "string"
                        }
                        properties[name] = prop
                    }
                    put("properties", properties)

                    params.required()?.let { put("required", it) }
                })
            }
        }
        return Gson().toJson(json)
    }

    override fun execute(paramsJsonString: String): String {
        // Not called with automaticToolCalling = false
        return """{"result": "ok"}"""
    }
}
