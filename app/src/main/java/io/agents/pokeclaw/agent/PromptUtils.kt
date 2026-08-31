// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog

/**
 * Prompt composition helpers (#45 persistent global prompt).
 *
 * Single responsibility: take a system prompt that some component is about to feed
 * to the LLM, and prepend the user's persistent global instructions when present.
 *
 * Empty / blank user global prompt = no-op, base prompt returned unchanged. This is
 * the disable signal — no separate boolean toggle, less state to misconfigure.
 */
object PromptUtils {
    private const val TAG = "PromptUtils"

    private const val PREFIX_HEADER = "User's persistent global instructions:"
    private const val SEPARATOR = "\n\n---\n\n"

    /**
     * Returns the base prompt, prepended with the user's global instructions if any.
     * Stable separator so downstream debug-report tooling can detect injection.
     */
    fun applyGlobalPrompt(basePrompt: String): String {
        val global = KVUtils.getGlobalPrompt()
        if (global.isBlank()) {
            XLog.d(TAG, "applyGlobalPrompt: no global prompt set, returning base (${basePrompt.length} chars)")
            return basePrompt
        }
        XLog.i(
            TAG,
            "applyGlobalPrompt: injecting global prompt (${global.length} chars) into base prompt (${basePrompt.length} chars)"
        )
        return "$PREFIX_HEADER\n$global$SEPARATOR$basePrompt"
    }
}
