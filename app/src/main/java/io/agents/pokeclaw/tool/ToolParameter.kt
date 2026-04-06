// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool

data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val isRequired: Boolean
)
