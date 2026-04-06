// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool

import com.blankj.utilcode.util.ScreenUtils

abstract class BaseTool {

    companion object {
        /** 工具描述语言，设为 true 使用中文描述，false 使用英文 */
        @JvmField
        var useChineseDescription: Boolean = true

        /** wait_after 参数的最大值（毫秒） */
        private const val MAX_WAIT_AFTER_MS = 10000L

        /**
         * 所有工具共用的 wait_after 参数定义。
         * 由 getParametersWithWaitAfter() 自动追加到每个工具的参数列表末尾。
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
     * 返回工具参数列表 + wait_after 通用参数。
     * 供 ToolBridge 注册工具规格时使用。
     */
    fun getParametersWithWaitAfter(): List<ToolParameter> {
        val params = getParameters().toMutableList()
        // 不给 wait / finish / get_screen_info 等观察类工具加 wait_after
        if (getName() !in listOf("wait", "finish", "get_screen_info", "take_screenshot", "get_installed_apps", "find_node_info", "scroll_to_find", "list_scheduled_tasks", "schedule_task", "cancel_scheduled_task")) {
            params.add(WAIT_AFTER_PARAM)
        }
        return params
    }

    /**
     * 执行工具并处理 wait_after 等待。
     * 供 ToolRegistry.executeTool() 调用。
     */
    fun executeWithWaitAfter(params: @JvmSuppressWildcards Map<String, Any>): ToolResult {
        val result = execute(params)
        // 执行成功后才等待
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

    /** 英文描述，子类必须实现 */
    abstract fun getDescriptionEN(): String

    /** 中文描述，子类必须实现 */
    abstract fun getDescriptionCN(): String

    /** 根据语言开关返回描述 */
    fun getDescription(): String =
        if (useChineseDescription) getDescriptionCN() else getDescriptionEN()

    /** 用于展示给用户看的中文名称，子类可覆写 */
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
     * 获取屏幕尺寸 [width, height]。
     */
    protected fun getScreenSize(): IntArray {
        return intArrayOf(ScreenUtils.getScreenWidth(), ScreenUtils.getScreenHeight())
    }

    /**
     * 校验坐标是否在屏幕范围内，超出则返回错误信息，合法返回 null。
     */
    protected fun validateCoordinates(x: Int, y: Int): String? {
        val size = getScreenSize()
        if (x < 0 || x >= size[0] || y < 0 || y >= size[1]) {
            return "Coordinates ($x, $y) out of screen bounds (${size[0]}x${size[1]}). Use get_screen_info to get valid coordinates."
        }
        return null
    }
}
