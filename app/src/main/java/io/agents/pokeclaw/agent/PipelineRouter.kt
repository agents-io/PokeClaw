// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import android.content.Context
import android.content.Intent
import io.agents.pokeclaw.tool.ToolRegistry
import io.agents.pokeclaw.utils.XLog

/**
 * 3-Tier Pipeline Router.
 *
 * Tier 1: Deterministic parser (regex) → 0 LLM calls
 * Tier 2: LLM classifier (1 call) → routes to skill or agent
 * Tier 3: UI agent loop (3-30 calls) → full perception/reasoning/action
 *
 * Architecture reference: DroidClaw's pipeline.ts
 */
class PipelineRouter(private val context: Context) {

    sealed class Route {
        /** Tier 1: Execute Android intent directly */
        data class DirectIntent(val intent: Intent, val description: String) : Route()

        /** Tier 1: Execute a tool directly (e.g., screenshot, back, home) */
        data class DirectTool(val toolName: String, val params: Map<String, Any>, val description: String) : Route()

        /** Tier 2: Execute a registered skill */
        data class Skill(val skillId: String, val params: Map<String, String>, val description: String) : Route()

        /** Tier 2/3: Run the full agent loop */
        data class AgentLoop(val task: String, val app: String? = null) : Route()

        /** Tier 2: Pure chat response (no phone control) */
        data class Chat(val task: String) : Route()
    }

    /**
     * Route a user task through the 3-tier pipeline.
     *
     * @param task user's task text
     * @return the routing decision
     */
    fun route(task: String): Route {
        // Tier 1: Deterministic regex matching
        val parseResult = TaskParser.parse(task)
        if (parseResult != null) {
            XLog.i(TAG, "Tier 1 match: ${parseResult.action} → ${parseResult.description}")

            // Intent-based action (call, alarm, settings, URL)
            if (parseResult.intent != null) {
                return Route.DirectIntent(parseResult.intent, parseResult.description)
            }

            // Tool-based action (screenshot, back, home, open app)
            if (parseResult.toolName != null) {
                return Route.DirectTool(
                    parseResult.toolName,
                    parseResult.toolParams ?: emptyMap(),
                    parseResult.description
                )
            }
        }

        // Tier 1 didn't match → fall through to Tier 2 (classification)
        // Note: Tier 2 requires an LLM call, which is done by the caller
        // (DefaultAgentService or TaskOrchestrator). This method returns
        // AgentLoop as the default fallback — the caller can optionally
        // run TaskClassifier first for smarter routing.
        XLog.i(TAG, "Tier 1 no match, falling through to agent loop: $task")
        return Route.AgentLoop(task)
    }

    /**
     * Execute a Tier 1 direct intent.
     */
    fun executeIntent(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            XLog.i(TAG, "Executed intent: ${intent.action}")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to execute intent: ${intent.action}", e)
        }
    }

    /**
     * Execute a Tier 1 direct tool call.
     */
    fun executeTool(toolName: String, params: Map<String, Any>): String {
        val result = ToolRegistry.getInstance().executeTool(toolName, params)
        XLog.i(TAG, "Executed tool: $toolName → ${if (result.isSuccess) "success" else result.error}")
        return result.data ?: result.error ?: ""
    }

    companion object {
        private const val TAG = "PipelineRouter"
    }
}
