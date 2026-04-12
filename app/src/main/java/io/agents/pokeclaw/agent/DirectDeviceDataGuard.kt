// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import java.util.Locale

/**
 * Enforces a simple rule for device-data requests:
 * if PokeClaw already has a direct tool for the requested data, the model must use it
 * before answering instead of falling back to a generic chatbot denial.
 */
internal class DirectDeviceDataGuard private constructor(
    private val match: Match?
) {

    data class DeterministicToolCall(
        val toolName: String,
        val params: Map<String, Any>,
    )

    data class Match(
        val taskText: String,
        val taskLabel: String,
        val allowedTools: Set<String>,
        val requiredAction: String,
    )

    private var attemptedDirectTool = false

    fun buildPromptSection(): String {
        val task = match ?: return ""
        return "\n\n" + """
            ## Task Guard: Direct Device Data
            This request is about the user's real phone data, not a generic chatbot-only question.
            You ARE allowed to access this data with PokeClaw tools.
            Never claim that you cannot access the user's clipboard, notifications, phone state, installed apps, or current screen when a matching tool exists.
            Empty or missing data is still a valid result. For example, an empty clipboard or no notifications should be reported plainly, not treated as a failure.

            Current task: "${task.taskText}"
            Use one of these tools first: ${task.allowedTools.joinToString(", ")}.
            Required action before completion: ${task.requiredAction}
            After reading the data, explain it clearly in normal language.
        """.trimIndent()
    }

    fun shouldBlockTextOnlyCompletion(): Boolean {
        return match != null && !attemptedDirectTool
    }

    fun buildCompletionCorrection(): String {
        val task = match ?: return "[System Guard] Continue the task instead of stopping."
        return "[System Guard] This is a ${task.taskLabel} request about the user's phone data. " +
            "Do not answer as if you lack device access. First use one of these tools: " +
            task.allowedTools.joinToString(", ") +
            ". Then explain the real result. Empty or missing data is still a valid answer."
    }

    fun maybeBlockFinish(): String? {
        val task = match ?: return null
        if (attemptedDirectTool) return null
        return "[System Guard] Do not call finish yet for this ${task.taskLabel} request. " +
            "You have not tried a direct phone-data tool yet. " +
            "Use one of these first: ${task.allowedTools.joinToString(", ")}. " +
            task.requiredAction + " Empty or missing data is still a valid result."
    }

    fun recordToolAttempt(toolName: String) {
        val task = match ?: return
        if (toolName in task.allowedTools) {
            attemptedDirectTool = true
        }
    }

    companion object {
        fun fromTask(task: String): DirectDeviceDataGuard {
            return DirectDeviceDataGuard(parse(task))
        }

        fun matchesNonInteractiveDeviceDataTask(task: String): Boolean {
            val match = parse(task) ?: return false
            return match.taskLabel != "screen-reading"
        }

        fun deterministicToolCall(task: String): DeterministicToolCall? {
            val normalized = normalize(task)
            return when {
                isClipboardDataRequest(normalized) ->
                    DeterministicToolCall("clipboard", mapOf("action" to "get"))

                normalized.contains("notif") || normalized.contains("notification") ->
                    DeterministicToolCall("get_notifications", emptyMap())

                normalized.contains("what apps do i have") ||
                    normalized.contains("installed apps") ||
                    normalized.contains("apps do i have") ->
                    DeterministicToolCall("get_installed_apps", emptyMap())

                normalized.contains("battery") ->
                    DeterministicToolCall("get_device_info", mapOf("category" to "battery"))

                normalized.contains("wifi") ->
                    DeterministicToolCall("get_device_info", mapOf("category" to "wifi"))

                normalized.contains("bluetooth") ->
                    DeterministicToolCall("get_device_info", mapOf("category" to "bluetooth"))

                normalized.contains("storage") ->
                    DeterministicToolCall("get_device_info", mapOf("category" to "storage"))

                normalized.contains("android version") ||
                    normalized.contains("phone temp") ||
                    normalized.contains("temperature") ->
                    DeterministicToolCall("get_device_info", mapOf("category" to if (normalized.contains("android version")) "device" else "battery"))

                else -> null
            }
        }

        private fun parse(task: String): Match? {
            val normalized = normalize(task)
            return when {
                isClipboardDataRequest(normalized) ->
                    Match(
                        taskText = task.trim(),
                        taskLabel = "clipboard",
                        allowedTools = setOf("clipboard"),
                        requiredAction = "Call clipboard(action=\"get\") before you answer.",
                    )

                normalized.contains("notif") || normalized.contains("notification") ->
                    Match(
                        taskText = task.trim(),
                        taskLabel = "notification",
                        allowedTools = setOf("get_notifications"),
                        requiredAction = "Call get_notifications() before you answer.",
                    )

                normalized.contains("battery") ||
                    normalized.contains("wifi") ||
                    normalized.contains("bluetooth") ||
                    normalized.contains("storage") ||
                    normalized.contains("android version") ||
                    normalized.contains("phone temp") ||
                    normalized.contains("temperature") ->
                    Match(
                        taskText = task.trim(),
                        taskLabel = "device info",
                        allowedTools = setOf("get_device_info"),
                        requiredAction = "Call get_device_info(category=...) with the matching category before you answer.",
                    )

                normalized.contains("what apps do i have") ||
                    normalized.contains("installed apps") ||
                    normalized.contains("apps do i have") ->
                    Match(
                        taskText = task.trim(),
                        taskLabel = "installed apps",
                        allowedTools = setOf("get_installed_apps"),
                        requiredAction = "Call get_installed_apps() before you answer.",
                    )

                normalized.contains("on my screen") ||
                    normalized.contains("on screen right now") ||
                    normalized.contains("read screen") ||
                    normalized.contains("what's on my screen") ||
                    normalized.contains("what is on my screen") ->
                    Match(
                        taskText = task.trim(),
                        taskLabel = "screen-reading",
                        allowedTools = setOf("get_screen_info"),
                        requiredAction = "Call get_screen_info() before you answer.",
                    )

                else -> null
            }
        }

        private fun normalize(value: String): String {
            return value.lowercase(Locale.US)
                .replace(Regex("""\s+"""), " ")
                .trim()
        }

        private fun isClipboardDataRequest(normalized: String): Boolean {
            if (normalized.contains("what i copied") || normalized.contains("what did i copy")) {
                return true
            }
            if (!normalized.contains("clipboard")) return false

            val directDataPhrases = listOf(
                "my clipboard",
                "the clipboard",
                "current clipboard",
                "on my clipboard",
                "in my clipboard",
                "read my clipboard",
                "read the clipboard",
                "check my clipboard",
                "show my clipboard",
                "explain my clipboard",
            )
            if (directDataPhrases.any { normalized.contains(it) }) return true

            val asksClipboardContents =
                (normalized.contains("what") || normalized.contains("read") || normalized.contains("show") || normalized.contains("explain")) &&
                    (normalized.contains("clipboard") && (normalized.contains(" my ") || normalized.contains(" the ") || normalized.startsWith("clipboard ")))
            return asksClipboardContents
        }
    }
}
