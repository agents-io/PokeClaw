// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import io.agents.pokeclaw.AppCapabilityCoordinator
import io.agents.pokeclaw.AppRequirement
import io.agents.pokeclaw.agent.llm.ModelConfigRepository
import io.agents.pokeclaw.service.MissedCallFollowUpManager

class MissedCallFlowController(
    private val activity: ComponentActivity,
) {

    companion object {
        private const val REQUEST_CODE = 202
    }

    fun start(prompt: String, conversationId: String) {
        if (!ModelConfigRepository.snapshot().defaultCloud.isConfigured) {
            Toast.makeText(activity, "Configure a default cloud model first", Toast.LENGTH_LONG).show()
            BackgroundChatBridge.postSystem(
                activity,
                conversationId,
                "Missed-call follow-up needs a configured default cloud model first."
            )
            return
        }

        if (AppCapabilityCoordinator.notificationAccessState(activity) != io.agents.pokeclaw.ServiceBindingState.READY) {
            Toast.makeText(activity, "Enable Notification Access first", Toast.LENGTH_LONG).show()
            BackgroundChatBridge.postSystem(
                activity,
                conversationId,
                "Missed-call follow-up needs Notification Access. Opening Settings..."
            )
            AppCapabilityCoordinator.openSystemSettings(activity, AppRequirement.NOTIFICATION_ACCESS)
            return
        }

        val missingRuntimePermissions = listOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
        ).filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingRuntimePermissions.isNotEmpty()) {
            activity.requestPermissions(missingRuntimePermissions.toTypedArray(), REQUEST_CODE)
            BackgroundChatBridge.postSystem(
                activity,
                conversationId,
                "Grant Call Log and SMS permissions, then tap Start again."
            )
            return
        }

        Toast.makeText(activity, MissedCallFollowUpManager.arm(activity, prompt, conversationId), Toast.LENGTH_SHORT).show()
    }
}
