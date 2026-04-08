// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.skill

import io.agents.pokeclaw.utils.XLog

/**
 * Registry of built-in and user-defined skills.
 * Skills are loaded at app startup and matched by trigger patterns.
 */
object SkillRegistry {

    private const val TAG = "SkillRegistry"
    private val skills = mutableMapOf<String, Skill>()

    /**
     * Register a skill. Replaces existing skill with same ID.
     */
    fun register(skill: Skill) {
        skills[skill.id] = skill
        XLog.d(TAG, "Registered skill: ${skill.id} (${skill.name})")
    }

    fun findById(id: String): Skill? = skills[id]

    fun getAll(): List<Skill> = skills.values.toList()

    fun getByCategory(category: SkillCategory): List<Skill> =
        skills.values.filter { it.category == category }

    fun getUserFacing(): List<Skill> =
        skills.values.filter { it.userFacing }

    /**
     * Find a skill that matches a task by trigger patterns.
     * Returns null if no skill matches.
     */
    fun findByTrigger(task: String): Skill? {
        val lower = task.lowercase()
        // Compound tasks with conjunctions should go to agent loop, not skills.
        // Skills are for simple, single-action commands only.
        if (lower.contains(" and ") || lower.contains(" then ") || lower.contains(" after ")) {
            XLog.d(TAG, "Compound task detected, skipping skill matching: $task")
            return null
        }
        return skills.values.find { skill ->
            skill.triggerPatterns.any { pattern ->
                try {
                    val regex = pattern.lowercase()
                        .replace(Regex("\\{\\w+\\}"), "(.+)")
                    Regex(regex).containsMatchIn(lower)
                } catch (e: Exception) {
                    XLog.w(TAG, "Invalid trigger pattern: $pattern", e)
                    false
                }
            }
        }
    }

    /**
     * Load built-in skills. Called once at app startup.
     *
     * Only simple, context-free, app-agnostic skills belong here.
     * Complex app-specific tasks (messaging, camera, etc.) go through the agent loop.
     */
    fun loadBuiltInSkills() {
        register(BuiltInSkills.searchInApp())
        register(BuiltInSkills.submitForm())
        register(BuiltInSkills.dismissPopup())
        register(BuiltInSkills.scrollAndRead())
        register(BuiltInSkills.copyScreenText())
        register(BuiltInSkills.acceptPermission())
        register(BuiltInSkills.swipeGesture())
        register(BuiltInSkills.goBack())
        register(BuiltInSkills.waitForContent())
        XLog.i(TAG, "Loaded ${skills.size} built-in skills")
    }

    fun clear() = skills.clear()
}
