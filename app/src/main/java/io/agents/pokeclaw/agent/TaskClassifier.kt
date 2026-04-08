// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.agents.pokeclaw.utils.XLog

/**
 * Tier 2: LLM-based task classifier.
 * Uses a single short LLM call (~200 word prompt) to classify the task
 * into: intent, skill, agent, chat, or impossible.
 *
 * Single-call LLM classification for task routing.
 * Handles ~20% of tasks with 1 LLM call, < 3 seconds.
 */
object TaskClassifier {

    private const val TAG = "TaskClassifier"

    data class Classification(
        @SerializedName("type") val type: String = "agent",
        @SerializedName("app") val app: String? = null,
        @SerializedName("skill_id") val skillId: String? = null,
        @SerializedName("sub_goal") val subGoal: String? = null,
        @SerializedName("params") val params: Map<String, String>? = null
    )

    /**
     * Build the classifier system prompt.
     * This prompt is much shorter than the agent loop's system prompt (~200 words vs ~2000 words).
     *
     * @param skillSummaries list of "id: description" for available skills
     */
    fun buildClassifierPrompt(skillSummaries: List<String>): String {
        val skillList = if (skillSummaries.isEmpty()) "None available"
            else skillSummaries.joinToString("\n") { "- $it" }

        return """You classify mobile phone tasks. Return JSON only, no explanation.

Output format: {"type": "...", "app": "...", "skill_id": "...", "sub_goal": "...", "params": {...}}

Types:
- "intent": can be done with a single Android intent (call, alarm, open URL, open settings)
- "skill": matches an available skill below — set skill_id and params
- "agent": requires UI interaction (tapping, scrolling, reading screen). Set app and sub_goal
- "chat": conversational question, no phone control needed
- "impossible": cannot be done on a phone

Available skills:
$skillList

Rules:
- If the task mentions "search" or "find" in an app → skill_id: "search_in_app"
- If the task involves messaging (WhatsApp, SMS, email) → type: "agent" (requires UI navigation)
- If task is ambiguous between skill and agent, prefer agent for complex tasks, skill for simple ones
- sub_goal should be a simplified version of the task for the agent loop
- app should be a common app name like "YouTube", "WhatsApp", "Chrome", "Clock"
"""
    }

    /**
     * Parse the LLM's JSON response into a Classification.
     * Handles common LLM response issues (markdown wrapping, extra text).
     */
    fun parseResponse(response: String): Classification {
        try {
            // Strip markdown code blocks if present
            var json = response.trim()
            if (json.startsWith("```")) {
                json = json.substringAfter("\n").substringBeforeLast("```").trim()
            }
            // Find JSON object
            val start = json.indexOf('{')
            val end = json.lastIndexOf('}')
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1)
            }
            return Gson().fromJson(json, Classification::class.java)
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to parse classifier response: $response", e)
            return Classification(type = "agent", subGoal = response)
        }
    }
}
