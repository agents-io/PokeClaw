// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.skill

/**
 * Built-in deterministic skills for common, context-free actions.
 *
 * Design principle: only include skills that are SIMPLE, RELIABLE, and APP-AGNOSTIC.
 * Complex app-specific tasks (WhatsApp, Gmail, Camera, etc.) should go through
 * the LLM agent loop, which can adapt to different UI layouts and languages.
 *
 * These skills save 1-10 LLM inference rounds for routine actions.
 */
object BuiltInSkills {

    fun searchInApp() = Skill(
        id = "search_in_app",
        name = "Search in App",
        description = "Type a search query and submit it. Use when the task says 'search for' or 'find' in any app.",
        category = SkillCategory.INPUT,
        estimatedStepsSaved = 5,
        parameters = listOf(
            SkillParameter("query", "string", true, "The search query to type")
        ),
        triggerPatterns = listOf(
            "search for {query}",
            "search {query}",
            "find {query}",
            "搜索 {query}",
            "搵 {query}"
        ),
        steps = listOf(
            SkillStep("get_screen_info", description = "Check for search bar"),
            SkillStep("find_and_tap", mapOf("text" to "Search"), description = "Tap search icon/bar", optional = true),
            SkillStep("input_text", mapOf("text" to "{query}"), description = "Type query"),
            SkillStep("system_key", mapOf("key" to "enter"), description = "Submit search"),
            SkillStep("wait", mapOf("duration_ms" to "2000"), description = "Wait for results"),
        ),
        fallbackGoal = "The search bar should have '{query}' typed. Just press enter or submit."
    )

    fun submitForm() = Skill(
        id = "submit_form",
        name = "Submit Form",
        description = "Find and tap the Send/Submit/Save button. Use after typing a message or filling a form.",
        category = SkillCategory.INPUT,
        estimatedStepsSaved = 4,
        parameters = emptyList(),
        triggerPatterns = listOf("submit", "send message", "press send"),
        steps = listOf(
            SkillStep("get_screen_info", description = "Look for submit button"),
            SkillStep("find_and_tap", mapOf("text" to "Send|Submit|Save|Post|Done"), description = "Tap submit button"),
            SkillStep("wait", mapOf("duration_ms" to "2000"), description = "Wait for submission"),
        ),
        fallbackGoal = "Find and tap the Send, Submit, or Save button on screen."
    )

    fun dismissPopup() = Skill(
        id = "dismiss_popup",
        name = "Dismiss Popup",
        description = "Close common popups, dialogs, and overlays. Use when a dialog blocks the task.",
        category = SkillCategory.DISMISS,
        estimatedStepsSaved = 3,
        parameters = emptyList(),
        triggerPatterns = listOf("dismiss", "close popup", "close dialog"),
        steps = listOf(
            SkillStep("get_screen_info", description = "Identify popup type"),
            SkillStep("find_and_tap", mapOf("text" to "OK"), description = "Tap OK", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Got it"), description = "Tap Got it", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Close"), description = "Tap Close", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Close app"), description = "Tap Close app", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Dismiss"), description = "Tap Dismiss", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Not now"), description = "Tap Not now", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Cancel"), description = "Tap Cancel", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Skip"), description = "Tap Skip", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Wait"), description = "Tap Wait", optional = true),
        ),
        fallbackGoal = "A popup or dialog is blocking. Find and tap the close/dismiss button."
    )

    fun scrollAndRead() = Skill(
        id = "scroll_and_read",
        name = "Scroll and Read",
        description = "Scroll down the page and collect all visible text content.",
        category = SkillCategory.GENERAL,
        estimatedStepsSaved = 10,
        parameters = listOf(
            SkillParameter("max_scrolls", "int", false, "Maximum number of scrolls", "5")
        ),
        triggerPatterns = listOf("read the page", "scroll and read", "read all content"),
        steps = listOf(
            SkillStep("get_screen_info", description = "Read current screen"),
            SkillStep("swipe", mapOf("direction" to "up"), description = "Scroll down"),
            SkillStep("get_screen_info", description = "Read after scroll"),
            SkillStep("swipe", mapOf("direction" to "up"), description = "Scroll down more"),
            SkillStep("get_screen_info", description = "Read after scroll"),
        ),
        fallbackGoal = "Scroll down the page and collect all visible text."
    )

    fun copyScreenText() = Skill(
        id = "copy_screen_text",
        name = "Copy Screen Text",
        description = "Extract all visible text from the current screen.",
        category = SkillCategory.GENERAL,
        estimatedStepsSaved = 3,
        parameters = emptyList(),
        triggerPatterns = listOf("copy text", "extract text", "read screen"),
        steps = listOf(
            SkillStep("get_screen_info", description = "Read screen content"),
        ),
        fallbackGoal = "Read and return all visible text on the screen."
    )

    fun acceptPermission() = Skill(
        id = "accept_permission",
        name = "Accept Permission",
        description = "Accept permission dialogs, terms, or consent popups. Use when a dialog asks to Allow, Accept, or Agree.",
        category = SkillCategory.DISMISS,
        estimatedStepsSaved = 3,
        parameters = emptyList(),
        triggerPatterns = listOf("accept permission", "allow permission", "grant access"),
        steps = listOf(
            SkillStep("get_screen_info", description = "Check for permission dialog"),
            SkillStep("find_and_tap", mapOf("text" to "Allow"), description = "Tap Allow", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "While using the app"), description = "Tap While using", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Accept"), description = "Tap Accept", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Agree"), description = "Tap Agree", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "Continue"), description = "Tap Continue", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "I agree"), description = "Tap I agree", optional = true),
            SkillStep("find_and_tap", mapOf("text" to "ALLOW"), description = "Tap ALLOW caps", optional = true),
        ),
        fallbackGoal = "A permission or consent dialog appeared. Find and tap the Allow/Accept/Agree button."
    )

    fun swipeGesture() = Skill(
        id = "swipe_gesture",
        name = "Swipe Screen",
        description = "Swipe the screen in a direction. Use for Tinder swipes, Instagram stories, carousels, or any swipeable content.",
        category = SkillCategory.NAVIGATION,
        estimatedStepsSaved = 2,
        parameters = listOf(
            SkillParameter("direction", "string", true, "Direction: left, right, up, or down")
        ),
        triggerPatterns = listOf(
            "swipe {direction}",
            "scroll {direction}"
        ),
        steps = listOf(
            SkillStep("swipe", mapOf("direction" to "{direction}"), description = "Swipe {direction}"),
            SkillStep("wait", mapOf("duration_ms" to "1000"), description = "Wait for animation"),
        ),
        fallbackGoal = "Swipe the screen {direction}."
    )

    fun goBack() = Skill(
        id = "go_back",
        name = "Go Back",
        description = "Press the back button to return to the previous screen.",
        category = SkillCategory.NAVIGATION,
        estimatedStepsSaved = 1,
        parameters = emptyList(),
        triggerPatterns = listOf("go back", "press back", "navigate back"),
        steps = listOf(
            SkillStep("system_key", mapOf("key" to "back"), description = "Press back"),
            SkillStep("wait", mapOf("duration_ms" to "1000"), description = "Wait for transition"),
        ),
        fallbackGoal = "Press the back button."
    )

    fun waitForContent() = Skill(
        id = "wait_for_content",
        name = "Wait for Content",
        description = "Wait for new content to appear on screen (loading, AI response, page update). Polls up to 15 seconds.",
        category = SkillCategory.GENERAL,
        estimatedStepsSaved = 5,
        parameters = emptyList(),
        triggerPatterns = listOf("wait for content", "wait for loading", "wait for response"),
        steps = listOf(
            SkillStep("get_screen_info", description = "Capture initial screen state"),
            SkillStep("wait", mapOf("duration_ms" to "3000"), description = "Wait 3s"),
            SkillStep("get_screen_info", description = "Check for new content"),
            SkillStep("wait", mapOf("duration_ms" to "3000"), description = "Wait 3s more"),
            SkillStep("get_screen_info", description = "Check again"),
            SkillStep("wait", mapOf("duration_ms" to "3000"), description = "Wait 3s more"),
            SkillStep("get_screen_info", description = "Final check"),
        ),
        fallbackGoal = "Wait for new content to appear on the screen. Check every 3 seconds."
    )
}
