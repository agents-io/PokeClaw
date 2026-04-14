// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import io.agents.pokeclaw.agent.llm.LocalBackendHealth
import io.agents.pokeclaw.agent.llm.ModelConfigRepository
import io.agents.pokeclaw.service.AutoReplyManager
import io.agents.pokeclaw.support.DebugReportManager
import io.agents.pokeclaw.tool.ToolRegistry
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * Debug-only broadcast receiver for triggering tasks via ADB without UI interaction.
 *
 * Usage:
 *   adb shell am broadcast -a io.agents.pokeclaw.DEBUG_TASK --es task "open my camera"
 *
 * Set Cloud LLM config (any provider):
 *   adb shell am broadcast -a io.agents.pokeclaw.DEBUG_TASK --es task "config:" \
 *     --es api_key "sk-..." --es model_name "gpt-4o-mini"
 *
 * With custom base URL (OpenRouter, Groq, Ollama, etc.):
 *   adb shell am broadcast -a io.agents.pokeclaw.DEBUG_TASK --es task "config:" \
 *     --es api_key "sk-..." --es base_url "https://api.openrouter.ai/v1" --es model_name "google/gemini-2.5-flash"
 */
class DebugTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!io.agents.pokeclaw.BuildConfig.DEBUG) return
        val directTool = intent.getStringExtra("tool")?.trim().orEmpty()
        val paramsJson = firstNonBlank(
            decodeBase64Extra(intent, "params_b64"),
            intent.getStringExtra("params_json")?.trim()
        ).orEmpty()
        val backendAction = intent.getStringExtra("backend_action")?.trim().orEmpty()
        val supportAction = intent.getStringExtra("support_action")?.trim().orEmpty()
        val simulateMessage = intent.getStringExtra("simulate_message")?.trim().orEmpty()
        val task = intent.getStringExtra("task") ?: "open my camera"
        breadcrumb(
            context,
            "received tool=$directTool backend_action=$backendAction support_action=$supportAction simulate_contact=${intent.getStringExtra("simulate_contact").orEmpty()} simulate_message=$simulateMessage task=$task"
        )
        if (backendAction.isNotEmpty()) {
            handleLocalBackendDebug(intent, backendAction)
            return
        }
        if (supportAction.isNotEmpty()) {
            handleSupportDebug(context, supportAction)
            return
        }
        if (directTool.isNotEmpty()) {
            executeTool(intent, directTool, paramsJson)
            return
        }
        if (simulateMessage.isNotEmpty()) {
            simulateIncomingMessage(intent, simulateMessage)
            return
        }

        XLog.i("DebugTaskReceiver", "Received debug task: $task")

        // Handle config command
        if (task.startsWith("config:")) {
            try {
                val apiKey = intent.getStringExtra("api_key")
                val baseUrl = intent.getStringExtra("base_url")
                val modelName = intent.getStringExtra("model_name")
                val provider = intent.getStringExtra("provider") ?: "OPENAI"
                if (provider == "LOCAL") {
                    // For local LLM, base_url = model file path
                    if (baseUrl != null) {
                        ModelConfigRepository.saveLocalDefault(
                            modelPath = baseUrl,
                            modelId = modelName ?: "",
                            activateNow = true,
                        )
                        KVUtils.setLlmBaseUrl(baseUrl)
                    }
                } else {
                    // For cloud LLM
                    val resolvedBaseUrl = baseUrl
                        ?: io.agents.pokeclaw.agent.CloudProvider.findProviderForModel(modelName ?: "")?.defaultBaseUrl
                        ?: "https://api.openai.com/v1"
                    ModelConfigRepository.saveCloudDefault(
                        providerName = provider,
                        modelId = modelName ?: "",
                        baseUrl = resolvedBaseUrl,
                        apiKey = apiKey ?: "",
                        activateNow = true,
                    )
                }
                val vm = io.agents.pokeclaw.ClawApplication.appViewModelInstance
                vm.updateAgentConfig()
                vm.initAgent()
                vm.afterInit()
                XLog.i("DebugTaskReceiver", "LLM configured: provider=$provider, model=${modelName}")
            } catch (e: Exception) {
                XLog.e("DebugTaskReceiver", "Failed to set config", e)
            }
            return
        }

        try {
            val vm = io.agents.pokeclaw.ClawApplication.appViewModelInstance
            vm.startTask(task, "debug_${System.currentTimeMillis()}") { /* no UI callback for debug */ }
            XLog.i("DebugTaskReceiver", "Task started: $task")
        } catch (e: Exception) {
            XLog.e("DebugTaskReceiver", "Failed to start task", e)
        }
    }

    private fun executeTool(intent: Intent, toolName: String, paramsJson: String) {
        try {
            val params = mutableMapOf<String, Any>()
            if (paramsJson.isNotEmpty()) {
                val json = JSONObject(paramsJson)
                json.keys().forEach { key ->
                    val value = json.get(key)
                    when (value) {
                        JSONObject.NULL -> {}
                        is Int, is Long, is Double, is Boolean -> params[key] = value
                        else -> params[key] = value.toString()
                    }
                }
            }
            intent.extras?.keySet()
                ?.filter { it.startsWith("param_") }
                ?.forEach { key ->
                    val paramName = key.removePrefix("param_")
                    val value = intent.extras?.get(key)
                    if (!paramName.isNullOrEmpty() && value != null) {
                        params[paramName] = value.toString()
                    }
                }

            XLog.i("DebugTaskReceiver", "Executing debug tool: $toolName params=$params")
            breadcrumb(io.agents.pokeclaw.ClawApplication.instance, "executeTool start tool=$toolName params=$params")
            val result = ToolRegistry.getInstance().executeTool(toolName, params)
            if (result.isSuccess) {
                XLog.i("DebugTaskReceiver", "Debug tool success: $toolName -> ${result.data}")
                breadcrumb(io.agents.pokeclaw.ClawApplication.instance, "executeTool success tool=$toolName data=${result.data}")
            } else {
                XLog.w("DebugTaskReceiver", "Debug tool failed: $toolName -> ${result.error}")
                breadcrumb(io.agents.pokeclaw.ClawApplication.instance, "executeTool fail tool=$toolName error=${result.error}")
            }
        } catch (e: Exception) {
            XLog.e("DebugTaskReceiver", "Failed to execute debug tool", e)
            breadcrumb(io.agents.pokeclaw.ClawApplication.instance, "executeTool exception tool=$toolName error=${e.message}")
        }
    }

    private fun simulateIncomingMessage(intent: Intent, simulateMessage: String) {
        try {
            val contact = intent.getStringExtra("simulate_contact")?.trim().orEmpty()
            if (contact.isEmpty()) {
                XLog.w("DebugTaskReceiver", "simulate_message ignored because simulate_contact is empty")
                return
            }
            val app = intent.getStringExtra("simulate_app")?.trim().orEmpty().ifEmpty { "WhatsApp" }
            val ensureTargetEnabled = intent.getBooleanExtra("simulate_enable", true)
            XLog.i(
                "DebugTaskReceiver",
                "Simulating incoming message: contact=$contact app=$app ensureTargetEnabled=$ensureTargetEnabled message='$simulateMessage'"
            )
            breadcrumb(
                io.agents.pokeclaw.ClawApplication.instance,
                "simulateIncomingMessage contact=$contact app=$app ensureTargetEnabled=$ensureTargetEnabled message=$simulateMessage"
            )
            AutoReplyManager.getInstance().debugSimulateIncomingMessage(
                app,
                contact,
                simulateMessage,
                ensureTargetEnabled,
            )
        } catch (e: Exception) {
            XLog.e("DebugTaskReceiver", "Failed to simulate incoming message", e)
        }
    }

    private fun handleLocalBackendDebug(intent: Intent, action: String) {
        try {
            when (action.lowercase()) {
                "status" -> {
                    XLog.i("DebugTaskReceiver", "Local backend status: ${LocalBackendHealth.debugStateSummary()}")
                    breadcrumb(io.agents.pokeclaw.ClawApplication.instance, "backend status ${LocalBackendHealth.debugStateSummary()}")
                }
                "force_cpu_safe" -> {
                    val reason = intent.getStringExtra("backend_reason")?.trim().orEmpty().ifEmpty { "debug" }
                    LocalBackendHealth.debugForceCpuSafe(reason)
                    XLog.i("DebugTaskReceiver", "Forced CPU-safe mode: ${LocalBackendHealth.debugStateSummary()}")
                    breadcrumb(io.agents.pokeclaw.ClawApplication.instance, "backend force_cpu_safe reason=$reason ${LocalBackendHealth.debugStateSummary()}")
                }
                "clear_cpu_safe" -> {
                    LocalBackendHealth.debugClearCpuSafeMode()
                    XLog.i("DebugTaskReceiver", "Cleared CPU-safe mode: ${LocalBackendHealth.debugStateSummary()}")
                    breadcrumb(io.agents.pokeclaw.ClawApplication.instance, "backend clear_cpu_safe ${LocalBackendHealth.debugStateSummary()}")
                }
                "clear_gpu_verified" -> {
                    LocalBackendHealth.debugClearGpuVerified()
                    XLog.i("DebugTaskReceiver", "Cleared GPU verified state: ${LocalBackendHealth.debugStateSummary()}")
                    breadcrumb(io.agents.pokeclaw.ClawApplication.instance, "backend clear_gpu_verified ${LocalBackendHealth.debugStateSummary()}")
                }
                "mark_pending_gpu_init" -> {
                    val modelPath = intent.getStringExtra("backend_model_path")?.trim().orEmpty()
                        .ifEmpty { "/debug/model.litertlm" }
                    LocalBackendHealth.debugMarkPendingGpuInit(modelPath)
                    XLog.i("DebugTaskReceiver", "Marked pending GPU init: ${LocalBackendHealth.debugStateSummary()}")
                    breadcrumb(io.agents.pokeclaw.ClawApplication.instance, "backend mark_pending_gpu_init model=$modelPath ${LocalBackendHealth.debugStateSummary()}")
                }
                "clear_pending_gpu_init" -> {
                    LocalBackendHealth.debugClearPendingGpuInit()
                    XLog.i("DebugTaskReceiver", "Cleared pending GPU init: ${LocalBackendHealth.debugStateSummary()}")
                    breadcrumb(io.agents.pokeclaw.ClawApplication.instance, "backend clear_pending_gpu_init ${LocalBackendHealth.debugStateSummary()}")
                }
                "recover_pending_gpu_crash" -> {
                    val recovered = LocalBackendHealth.recoverPendingGpuCrashIfNeeded()
                    XLog.i(
                        "DebugTaskReceiver",
                        "Recover pending GPU crash recovered=$recovered: ${LocalBackendHealth.debugStateSummary()}"
                    )
                    breadcrumb(io.agents.pokeclaw.ClawApplication.instance, "backend recover_pending_gpu_crash recovered=$recovered ${LocalBackendHealth.debugStateSummary()}")
                }
                else -> {
                    XLog.w("DebugTaskReceiver", "Unknown backend_action=$action")
                    breadcrumb(io.agents.pokeclaw.ClawApplication.instance, "backend unknown action=$action")
                }
            }
        } catch (e: Exception) {
            XLog.e("DebugTaskReceiver", "Failed backend_action=$action", e)
            breadcrumb(io.agents.pokeclaw.ClawApplication.instance, "backend exception action=$action error=${e.message}")
        }
    }

    private fun handleSupportDebug(context: Context, action: String) {
        try {
            when (action.lowercase()) {
                "build_debug_report" -> {
                    val output = DebugReportManager.buildReport(context)
                    XLog.i(
                        "DebugTaskReceiver",
                        "Built debug report: ${output.absolutePath} (${output.length()} bytes)"
                    )
                    breadcrumb(context, "support build_debug_report path=${output.absolutePath} bytes=${output.length()}")
                }
                else -> {
                    XLog.w("DebugTaskReceiver", "Unknown support_action=$action")
                    breadcrumb(context, "support unknown action=$action")
                }
            }
        } catch (e: Exception) {
            XLog.e("DebugTaskReceiver", "Failed support_action=$action", e)
            breadcrumb(context, "support exception action=$action error=${e.message}")
        }
    }

    private fun decodeBase64Extra(intent: Intent, key: String): String? {
        val encoded = intent.getStringExtra(key)
        if (encoded.isNullOrBlank()) return null
        return try {
            String(Base64.decode(encoded, Base64.DEFAULT)).trim()
        } catch (e: IllegalArgumentException) {
            XLog.w("DebugTaskReceiver", "Invalid base64 extra for $key", e)
            null
        }
    }

    private fun firstNonBlank(vararg values: String?): String? {
        for (value in values) {
            if (!value.isNullOrBlank()) return value.trim()
        }
        return null
    }

    private fun breadcrumb(context: Context?, message: String) {
        if (context == null) return
        runCatching {
            val dir = File(context.cacheDir, "debug_reports").apply { mkdirs() }
            val file = File(dir, "debug-receiver.log")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            file.appendText("[$timestamp] $message\n")
        }
    }
}
