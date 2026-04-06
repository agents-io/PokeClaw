// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.server

import android.content.Context
import io.agents.pokeclaw.BuildConfig
import io.agents.pokeclaw.channel.ChannelManager
import io.agents.pokeclaw.tool.ToolRegistry
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.agents.pokeclaw.utils.XLog
import fi.iki.elonen.NanoHTTPD

/**
 * LAN HTTP configuration server
 * Provides an H5 page for configuring DingTalk/FeiShu keys in a desktop browser
 */
class ConfigServer(
    private val context: Context,
    port: Int = PORT
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "ConfigServer"
        const val PORT = 9527
        private const val MIME_HTML = "text/html"
        private const val MIME_JSON = "application/json"
    }

    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        // CORS preflight request
        if (session.method == Method.OPTIONS) {
            return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
        }

        val uri = session.uri
        val method = session.method

        return try {
            when {
                (uri == "/" || uri == "/index.html") && method == Method.GET -> serveHtml()
                uri == "/api/channels" && method == Method.GET -> handleGetChannels()
                uri == "/api/channels" && method == Method.POST -> handlePostChannels(session)
                uri == "/api/llm" && method == Method.GET -> handleGetLlm()
                uri == "/api/llm" && method == Method.POST -> handlePostLlm(session)
                uri == "/debug.html" && method == Method.GET && BuildConfig.DEBUG -> serveDebugHtml()
                uri == "/api/debug/tools" && method == Method.GET && BuildConfig.DEBUG -> handleGetTools()
                uri == "/api/debug/execute" && method == Method.POST && BuildConfig.DEBUG -> handleExecuteTool(session)
                uri == "/api/debug/screen-full" && method == Method.GET && BuildConfig.DEBUG -> handleGetScreenFull()
                uri.startsWith("/api/debug/file") && method == Method.GET && BuildConfig.DEBUG -> handleServeFile(session)
                else -> corsResponse(
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND, MIME_JSON,
                        """{"code":-1,"message":"not found"}"""
                    )
                )
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Server error: ${e.message}")
            corsResponse(
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_JSON,
                    """{"code":-1,"message":"${e.message}"}"""
                )
            )
        }
    }

    private fun serveHtml(): Response {
        val inputStream = context.assets.open("web/index.html")
        val html = inputStream.bufferedReader().use { it.readText() }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_HTML, html))
    }

    private fun handleGetChannels(): Response {
        val data = JsonObject().apply {
            addProperty("dingtalkAppKey", KVUtils.getDingtalkAppKey())
            addProperty("dingtalkAppSecret", KVUtils.getDingtalkAppSecret())
            addProperty("feishuAppId", KVUtils.getFeishuAppId())
            addProperty("feishuAppSecret", KVUtils.getFeishuAppSecret())
            addProperty("qqAppId", KVUtils.getQqAppId())
            addProperty("qqAppSecret", KVUtils.getQqAppSecret())
            addProperty("discordBotToken", KVUtils.getDiscordBotToken())
            addProperty("telegramBotToken", KVUtils.getTelegramBotToken())
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handlePostChannels(session: IHTTPSession): Response {
        // NanoHTTPD requires parseBody before reading POST body
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return corsResponse(
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"code":-1,"message":"invalid json"}"""
                )
            )
        }

        var reinitDingtalk = false
        var reinitFeishu = false
        var reinitQQ = false
        var reinitDiscord = false
        var reinitTelegram = false

        // DingTalk config
        if (json.has("dingtalkAppKey")) {
            val value = json.get("dingtalkAppKey").asString
            KVUtils.setDingtalkAppKey(value)
            reinitDingtalk = true
        }
        if (json.has("dingtalkAppSecret")) {
            val value = json.get("dingtalkAppSecret").asString
            // Skip if value is masked
            if (!isMaskedValue(value)) {
                KVUtils.setDingtalkAppSecret(value)
                reinitDingtalk = true
            }
        }

        // FeiShu config
        if (json.has("feishuAppId")) {
            val value = json.get("feishuAppId").asString
            KVUtils.setFeishuAppId(value)
            reinitFeishu = true
        }
        if (json.has("feishuAppSecret")) {
            val value = json.get("feishuAppSecret").asString
            if (!isMaskedValue(value)) {
                KVUtils.setFeishuAppSecret(value)
                reinitFeishu = true
            }
        }

        // QQ config
        if (json.has("qqAppId")) {
            val value = json.get("qqAppId").asString
            KVUtils.setQqAppId(value)
            reinitQQ = true
        }
        if (json.has("qqAppSecret")) {
            val value = json.get("qqAppSecret").asString
            if (!isMaskedValue(value)) {
                KVUtils.setQqAppSecret(value)
                reinitQQ = true
            }
        }

        // Discord config
        if (json.has("discordBotToken")) {
            val value = json.get("discordBotToken").asString
            if (!isMaskedValue(value)) {
                KVUtils.setDiscordBotToken(value)
                reinitDiscord = true
            }
        }

        // Telegram config
        if (json.has("telegramBotToken")) {
            val value = json.get("telegramBotToken").asString
            if (!isMaskedValue(value)) {
                KVUtils.setTelegramBotToken(value)
                reinitTelegram = true
            }
        }

        // Re-initialize the corresponding channel
        if (reinitDingtalk) {
            ChannelManager.reinitDingTalkFromStorage()
        }
        if (reinitFeishu) {
            ChannelManager.reinitFeiShuFromStorage()
        }
        if (reinitQQ) {
            ChannelManager.reinitQQFromStorage()
        }
        if (reinitDiscord) {
            ChannelManager.reinitDiscordFromStorage()
        }
        if (reinitTelegram) {
            ChannelManager.reinitTelegramFromStorage()
        }

        // Notify Settings page to refresh binding status
        if (reinitDingtalk || reinitFeishu || reinitQQ || reinitDiscord || reinitTelegram) {
            ConfigServerManager.notifyConfigChanged()
        }

        val result = JsonObject().apply {
            addProperty("code", 0)
            addProperty("message", "ok")
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handleGetLlm(): Response {
        val apiKey = KVUtils.getLlmApiKey()
        val data = JsonObject().apply {
            addProperty("llmApiKey", apiKey)
            addProperty("llmBaseUrl", KVUtils.getLlmBaseUrl())
            addProperty("llmModelName", KVUtils.getLlmModelName())
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handlePostLlm(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return corsResponse(
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"code":-1,"message":"invalid json"}"""
                )
            )
        }

        if (json.has("llmApiKey")) {
            val value = json.get("llmApiKey").asString
            if (!isMaskedValue(value)) {
                KVUtils.setLlmApiKey(value)
            }
        }
        if (json.has("llmBaseUrl")) {
            KVUtils.setLlmBaseUrl(json.get("llmBaseUrl").asString)
        }
        if (json.has("llmModelName")) {
            val value = json.get("llmModelName").asString.trim()
            KVUtils.setLlmModelName(if (value.isEmpty()) "" else value)
        }

        ConfigServerManager.notifyConfigChanged()

        val result = JsonObject().apply {
            addProperty("code", 0)
            addProperty("message", "ok")
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    // ==================== Debug (DEBUG builds only) ====================
    
    private fun handleGetScreenFull(): Response {
        val service = io.agents.pokeclaw.service.ClawAccessibilityService.getInstance()
            ?: return corsResponse(
                newFixedLengthResponse(
                    Response.Status.OK, MIME_JSON,
                    """{"code":-1,"message":"Accessibility service is not running"}"""
                )
            )
        val tree = service.screenTreeFull
        val data = JsonObject().apply {
            addProperty("success", tree != null)
            addProperty("data", tree ?: "")
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun serveDebugHtml(): Response {
        val inputStream = context.assets.open("web/debug.html")
        val html = inputStream.bufferedReader().use { it.readText() }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_HTML, html))
    }

    private fun handleGetTools(): Response {
        val tools = ToolRegistry.getAllTools()
        val arr = JsonArray()
        for (tool in tools) {
            val obj = JsonObject().apply {
                addProperty("name", tool.getName())
                addProperty("displayName", tool.getDisplayName())
                addProperty("description", tool.getDescription())
                val params = JsonArray()
                for (p in tool.getParameters()) {
                    params.add(JsonObject().apply {
                        addProperty("name", p.name)
                        addProperty("type", p.type)
                        addProperty("description", p.description)
                        addProperty("required", p.isRequired)
                    })
                }
                add("parameters", params)
            }
            arr.add(obj)
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", arr)
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handleExecuteTool(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return corsResponse(
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"code":-1,"message":"invalid json"}"""
                )
            )
        }

        val toolName = json.get("tool")?.asString ?: return corsResponse(
            newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"missing tool name"}"""
            )
        )

        val params = mutableMapOf<String, Any>()
        try {
            json.getAsJsonObject("params")?.entrySet()?.forEach { (key, value) ->
                when {
                    value.isJsonNull -> {}
                    !value.isJsonPrimitive -> params[key] = value.toString()
                    value.asJsonPrimitive.isNumber -> params[key] = value.asNumber
                    value.asJsonPrimitive.isBoolean -> params[key] = value.asBoolean
                    else -> params[key] = value.asString
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Debug param parse error: ${e.message}")
        }

        XLog.d(TAG, "Debug execute: $toolName params=$params")

        val toolResult = try {
            ToolRegistry.executeTool(toolName, params)
        } catch (e: Exception) {
            XLog.e(TAG, "Debug execute error", e)
            ToolResult.error("Exception: ${e.message}")
        }

        val data = JsonObject().apply {
            addProperty("success", toolResult.isSuccess)
            addProperty("data", toolResult.data)
            addProperty("error", toolResult.error)
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handleServeFile(session: IHTTPSession): Response {
        val path = session.parms["path"] ?: return corsResponse(
            newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"missing path param"}"""
            )
        )
        // Security check: only allow access to files inside the cache directory
        val cacheDir = context.cacheDir.absolutePath
        val file = java.io.File(path)
        if (!file.exists() || !file.absolutePath.startsWith(cacheDir)) {
            return corsResponse(
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_JSON,
                    """{"code":-1,"message":"file not found or access denied"}"""
                )
            )
        }
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, mime, file.inputStream(), file.length()))
    }

    /**
     * Mask: show only last 4 characters, replace the rest with *
     */
    private fun maskSecret(secret: String): String {
        if (secret.isEmpty()) return ""
        if (secret.length <= 4) return secret
        return "*".repeat(secret.length - 4) + secret.takeLast(4)
    }

    /**
     * Check whether a value has been masked (contains *)
     */
    private fun isMaskedValue(value: String): Boolean {
        return value.contains("*")
    }

    private fun corsResponse(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return response
    }
}
