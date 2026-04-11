// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.os.Handler
import android.os.Looper
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.AppViewModel
import io.agents.pokeclaw.service.AutoReplyManager
import io.agents.pokeclaw.service.ForegroundService
import io.agents.pokeclaw.service.MissedCallFollowUpManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns active-task shell state for the top task bar and monitor stop actions.
 *
 * ComposeChatActivity observes the exposed state; it no longer polls AutoReplyManager directly.
 */
class ActiveTaskShellController(
    private val appViewModel: AppViewModel,
) {

    private val autoReplyManager = AutoReplyManager.getInstance()
    private val missedCallFollowUpManager = MissedCallFollowUpManager
    private val handler = Handler(Looper.getMainLooper())
    private val _activeTasks = MutableStateFlow<List<String>>(emptyList())
    private val _monitorActive = MutableStateFlow(false)
    private val _missedCallActive = MutableStateFlow(false)

    val activeTasks: StateFlow<List<String>> = _activeTasks.asStateFlow()
    val monitorActive: StateFlow<Boolean> = _monitorActive.asStateFlow()
    val missedCallActive: StateFlow<Boolean> = _missedCallActive.asStateFlow()

    private val poller = object : Runnable {
        override fun run() {
            refreshActiveTasks()
            handler.postDelayed(this, 2000)
        }
    }

    fun onResume() {
        refreshActiveTasks()
        handler.removeCallbacks(poller)
        handler.post(poller)
    }

    fun onPause() {
        handler.removeCallbacks(poller)
    }

    fun stopTask(contact: String): String {
        val missedCallLabel = missedCallFollowUpManager.activeLabel()
        if (missedCallLabel != null && contact == missedCallLabel) {
            refreshActiveTasks()
            return missedCallFollowUpManager.stop(ClawApplication.instance)
        }

        autoReplyManager.removeContact(contact)
        if (autoReplyManager.monitoredContacts.isEmpty()) {
            autoReplyManager.isEnabled = false
        }
        refreshActiveTasks()
        ForegroundService.resetToIdle(ClawApplication.instance)
        return "Stopped monitoring $contact"
    }

    fun stopAllTasks(): String {
        var requestedTaskStop = false
        if (appViewModel.isTaskRunning()) {
            appViewModel.stopTask()
            requestedTaskStop = true
        }

        var stoppedMonitoring = false
        if (autoReplyManager.isEnabled) {
            autoReplyManager.stopAll()
            stoppedMonitoring = true
        }

        var stoppedMissedCallFollowUp = false
        if (missedCallFollowUpManager.isEnabled()) {
            missedCallFollowUpManager.stop(ClawApplication.instance)
            stoppedMissedCallFollowUp = true
        }

        refreshActiveTasks()
        ForegroundService.resetToIdle(ClawApplication.instance)
        return when {
            requestedTaskStop -> "Stopping current task..."
            stoppedMissedCallFollowUp -> "Stopped missed-call follow-up"
            stoppedMonitoring -> "All tasks stopped"
            else -> "No active tasks"
        }
    }

    private fun refreshActiveTasks() {
        val monitorTasks = if (autoReplyManager.isEnabled) {
            autoReplyManager.monitoredContacts.toList()
        } else {
            emptyList()
        }
        val backgroundTasks = monitorTasks.toMutableList()
        missedCallFollowUpManager.activeLabel()?.let { backgroundTasks.add(it) }
        _monitorActive.value = monitorTasks.isNotEmpty()
        _missedCallActive.value = missedCallFollowUpManager.isEnabled()
        _activeTasks.value = backgroundTasks
    }
}
