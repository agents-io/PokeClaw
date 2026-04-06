// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

interface StreamingListener {
    fun onPartialText(token: String)
    fun onComplete(response: LlmResponse)
    fun onError(error: Throwable)
}
