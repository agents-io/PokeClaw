// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool

class ToolResult private constructor(
    val isSuccess: Boolean,
    val data: String?,
    val error: String?
) {
    companion object {
        @JvmStatic
        fun success(data: String): ToolResult = ToolResult(true, data, null)

        @JvmStatic
        fun error(error: String): ToolResult = ToolResult(false, null, error)
    }

    override fun toString(): String = if (isSuccess) {
        "ToolResult{success=true, data='$data'}"
    } else {
        "ToolResult{success=false, error='$error'}"
    }
}
