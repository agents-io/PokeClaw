// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.agents.pokeclaw.appViewModel
import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog

/**
 * Debug-only broadcast receiver for triggering tasks via ADB without UI interaction.
 *
 * Usage:
 *   adb shell am broadcast -a io.agents.pokeclaw.DEBUG_TASK --es task "open my camera"
 *
 * Set Cloud LLM config:
 *   adb shell am broadcast -a io.agents.pokeclaw.DEBUG_TASK --es task "config:" \
 *     --es api_key "sk-..." --es base_url "https://api.openai.com/v1" --es model_name "gpt-4o-mini"
 */
class DebugTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val task = intent.getStringExtra("task") ?: "open my camera"
        XLog.i("DebugTaskReceiver", "Received debug task: $task")

        // Handle config command
        if (task.startsWith("config:")) {
            try {
                val apiKey = intent.getStringExtra("api_key")
                val baseUrl = intent.getStringExtra("base_url")
                val modelName = intent.getStringExtra("model_name")
                if (apiKey != null) KVUtils.setLlmApiKey(apiKey)
                if (baseUrl != null) KVUtils.setLlmBaseUrl(baseUrl)
                if (modelName != null) KVUtils.setLlmModelName(modelName)
                KVUtils.setLlmProvider("OPENAI")
                val vm = io.agents.pokeclaw.ClawApplication.appViewModelInstance
                vm.updateAgentConfig()
                vm.initAgent()
                vm.afterInit()
                XLog.i("DebugTaskReceiver", "Cloud LLM configured: model=${modelName}, key=${apiKey?.take(10)}...")
            } catch (e: Exception) {
                XLog.e("DebugTaskReceiver", "Failed to set config", e)
            }
            return
        }

        try {
            val vm = io.agents.pokeclaw.ClawApplication.appViewModelInstance
            vm.startNewTask(Channel.LOCAL, task, "debug_${System.currentTimeMillis()}")
            XLog.i("DebugTaskReceiver", "Task started: $task")
        } catch (e: Exception) {
            XLog.e("DebugTaskReceiver", "Failed to start task", e)
        }
    }
}
