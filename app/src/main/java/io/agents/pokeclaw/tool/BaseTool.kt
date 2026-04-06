// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool

import com.blankj.utilcode.util.ScreenUtils

abstract class BaseTool {

    companion object {
        /** Tool description language: true = Chinese, false = English */
        @JvmField
        var useChineseDescription: Boolean = false

        /** Maximum value for the wait_after parameter (milliseconds) */
        private const val MAX_WAIT_AFTER_MS = 10000L

        /**
         * Shared wait_after parameter definition used by all tools.
         * Automatically appended to the end of each tool's parameter list by getParametersWithWaitAfter().
         */
        @JvmStatic
        val WAIT_AFTER_PARAM = ToolParameter(
            "wait_after",
            "integer",
            "Optional: milliseconds to wait after this action completes (e.g. 2000 for page load). Default 0 (no wait).",
            false
        )
    }

    abstract fun getName(): String
    abstract fun getParameters(): List<ToolParameter>
    abstract fun execute(params: @JvmSuppressWildcards Map<String, Any>): ToolResult

    /**
     * Returns the tool parameter list plus the shared wait_after parameter.
     * Used by ToolBridge when registering tool specifications.
     */
    fun getParametersWithWaitAfter(): List<ToolParameter> {
        val params = getParameters().toMutableList()
        // Do not add wait_after to observation tools like wait / finish / get_screen_info
        if (getName() !in listOf("wait", "finish", "get_screen_info", "take_screenshot", "get_installed_apps", "find_node_info", "scroll_to_find", "list_scheduled_tasks", "schedule_task", "cancel_scheduled_task")) {
            params.add(WAIT_AFTER_PARAM)
        }
        return params
    }

    /**
     * Execute the tool and handle wait_after delay.
     * Called by ToolRegistry.executeTool().
     */
    fun executeWithWaitAfter(params: @JvmSuppressWildcards Map<String, Any>): ToolResult {
        val result = execute(params)
        // Only wait if execution succeeded
        if (result.isSuccess) {
            val waitMs = optionalLong(params, "wait_after", 0)
            if (waitMs in 1..MAX_WAIT_AFTER_MS) {
                try {
                    Thread.sleep(waitMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        return result
    }

    /** English description, subclasses must implement */
    abstract fun getDescriptionEN(): String

    /** Chinese description, subclasses must implement */
    abstract fun getDescriptionCN(): String

    /** Returns description based on language toggle */
    fun getDescription(): String =
        if (useChineseDescription) getDescriptionCN() else getDescriptionEN()

    /** Display name shown to the user; subclasses may override */
    open fun getDisplayName(): String = getName()

    // === Parameter helpers ===

    protected fun requireString(params: @JvmSuppressWildcards Map<String, Any>, key: String): String {
        return params[key]?.toString()
            ?: throw IllegalArgumentException("Missing required parameter: $key")
    }

    protected fun requireInt(params: @JvmSuppressWildcards Map<String, Any>, key: String): Int {
        val value = params[key] ?: throw IllegalArgumentException("Missing required parameter: $key")
        return when (value) {
            is Number -> value.toInt()
            else -> value.toString().toInt()
        }
    }

    protected fun requireLong(params: @JvmSuppressWildcards Map<String, Any>, key: String): Long {
        val value = params[key] ?: throw IllegalArgumentException("Missing required parameter: $key")
        return when (value) {
            is Number -> value.toLong()
            else -> value.toString().toLong()
        }
    }

    protected fun optionalInt(params: @JvmSuppressWildcards Map<String, Any>, key: String, defaultValue: Int): Int {
        val value = params[key] ?: return defaultValue
        return when (value) {
            is Number -> value.toInt()
            else -> value.toString().toInt()
        }
    }

    protected fun optionalLong(params: @JvmSuppressWildcards Map<String, Any>, key: String, defaultValue: Long): Long {
        val value = params[key] ?: return defaultValue
        return when (value) {
            is Number -> value.toLong()
            else -> value.toString().toLong()
        }
    }

    protected fun optionalString(params: @JvmSuppressWildcards Map<String, Any>, key: String, defaultValue: String): String {
        return params[key]?.toString() ?: defaultValue
    }

    protected fun optionalBoolean(params: @JvmSuppressWildcards Map<String, Any>, key: String, defaultValue: Boolean): Boolean {
        val value = params[key] ?: return defaultValue
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> value.toString().toBoolean()
        }
    }

    // === Screen bounds helpers ===

    /**
     * Get screen size [width, height].
     */
    protected fun getScreenSize(): IntArray {
        return intArrayOf(ScreenUtils.getScreenWidth(), ScreenUtils.getScreenHeight())
    }

    /**
     * Validate that coordinates are within screen bounds.
     * Returns an error message if out of bounds, or null if valid.
     */
    protected fun validateCoordinates(x: Int, y: Int): String? {
        val size = getScreenSize()
        if (x < 0 || x >= size[0] || y < 0 || y >= size[1]) {
            return "Coordinates ($x, $y) out of screen bounds (${size[0]}x${size[1]}). Use get_screen_info to get valid coordinates."
        }
        return null
    }
}
