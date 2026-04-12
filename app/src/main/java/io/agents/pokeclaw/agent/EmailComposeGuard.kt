// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

/**
 * Narrow execution guard for explicit email-compose tasks.
 *
 * We only activate this for requests that clearly mean "open an email composer and
 * draft something", not for generic chat requests about writing. The goal is to stop
 * the agent from claiming success with a text-only draft before it has attempted any
 * UI work inside an email app.
 */
internal class EmailComposeGuard private constructor(
    private val match: Match?
) {

    data class Match(
        val taskText: String
    )

    private var attemptedComposeFlow = false
    fun buildPromptSection(): String {
        val task = match ?: return ""
        return "\n\n" + """
            ## Task Guard: Compose Email Draft
            This task means: create an email draft in an email app, not just reply with draft text in chat.
            Required execution steps before completion:
            1. Open an email app (for example Gmail) and start a compose/new-draft flow.
            2. Fill the visible fields one at a time with input_text.
            3. If the task does not name a recipient, leave the recipient field blank but still fill a subject and body.
            4. Inspect the compose screen to confirm the draft is visible.
            5. Only then call finish(summary="draft ready to review").
            Never press Send unless the user explicitly asked you to send the email.
            Never satisfy this task by returning draft text alone without opening an email composer.
            Current email-draft task: "${task.taskText}"
        """.trimIndent()
    }

    fun shouldBlockTextOnlyCompletion(): Boolean {
        return match != null && !attemptedComposeFlow
    }

    fun buildCompletionCorrection(): String {
        return "[System Guard] This is an email-compose task. " +
            "Do not stop with draft text only. Open an email app, start a compose flow, " +
            "fill the visible draft fields, inspect the draft screen, and only then finish."
    }

    fun maybeBlockFinish(screenInfo: String? = null): String? {
        if (match == null || attemptedComposeFlow) return null
        val nodeHint = buildNodeHint(screenInfo)
        return "[System Guard] Do not call finish yet for this email-compose task. " +
            "You have not attempted any in-app compose action yet. " +
            "Open an email app, enter compose mode, fill the draft fields, then finish." +
            nodeHint
    }

    fun recordToolAttempt(toolName: String) {
        if (match == null) return
        if (toolName in UI_COMPOSE_TOOLS) {
            attemptedComposeFlow = true
        }
    }

    fun recordSuccessfulTool(toolName: String) {
        if (match == null) return
        if (toolName == "input_text" || toolName == "type_text") {
            attemptedComposeFlow = true
        }
    }

    companion object {
        private val UI_COMPOSE_TOOLS = setOf(
            "open_app",
            "tap",
            "tap_node",
            "find_and_tap",
            "input_text",
            "type_text",
            "scroll_to_find",
            "system_key"
        )

        fun fromTask(task: String): EmailComposeGuard {
            val trimmed = task.trim()
            val normalized = trimmed.lowercase()
            val startsWithComposeVerb = normalized.startsWith("write ") ||
                normalized.startsWith("compose ") ||
                normalized.startsWith("draft ") ||
                normalized.startsWith("send ")
            val mentionsEmail = normalized.contains(" email") || normalized.startsWith("email ")
            val match = if (startsWithComposeVerb && mentionsEmail) Match(trimmed) else null
            return EmailComposeGuard(match)
        }

        private fun buildNodeHint(screenInfo: String?): String {
            if (screenInfo.isNullOrBlank()) return ""
            val preferredNode = screenInfo.lineSequence()
                .map { it.trim() }
                .firstOrNull { line ->
                    (line.contains("compose", ignoreCase = true) ||
                        line.contains("subject", ignoreCase = true) ||
                        line.contains("to", ignoreCase = true)) &&
                        line.contains("[n")
                }
                ?.let { extractNodeId(it) }

            if (preferredNode != null) {
                return " Current screen suggests a relevant compose element at node_id=\"$preferredNode\"."
            }

            val anyEditNode = screenInfo.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.contains(" edit") }
                ?.let { extractNodeId(it) }

            return if (anyEditNode != null) {
                " Current screen has an editable field at node_id=\"$anyEditNode\"."
            } else {
                ""
            }
        }

        private fun extractNodeId(line: String): String? {
            return Regex("""\[(n\d+)]""").find(line)?.groupValues?.getOrNull(1)
        }
    }
}
