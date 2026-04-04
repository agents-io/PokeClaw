package com.apk.claw.android.ui.chat

/**
 * Represents a single message in the chat UI.
 */
data class ChatMessage(
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Role {
        USER,       // User's message
        ASSISTANT,  // AI response
        TOOL,       // Tool call result (tap, swipe, etc.)
        SYSTEM      // Status messages (model loading, errors)
    }
}
