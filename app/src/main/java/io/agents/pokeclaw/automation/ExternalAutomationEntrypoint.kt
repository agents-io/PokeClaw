// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.automation

import android.content.Context
import android.content.Intent
import io.agents.pokeclaw.appViewModel
import io.agents.pokeclaw.ui.chat.ComposeChatActivity
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog

object ExternalAutomationEntrypoint {
    private const val TAG = "ExternalAutomation"
    private const val MAX_LOG_TEXT = 120

    fun handle(context: Context, intent: Intent?, launchFlags: Int): Boolean {
        if (intent == null) return false
        if (!isTargetedToPokeClaw(context, intent)) {
            XLog.w(TAG, "Rejected non-targeted external automation request: ${intent.action}")
            return false
        }

        val request = ExternalAutomationContract.parse(intent.action) { key ->
            intent.getStringExtra(key)
        }
        if (request == null) {
            rejectFromIntent(context, intent, "Missing task/chat payload")
            return false
        }

        if (!KVUtils.isExternalAutomationEnabled()) {
            XLog.w(TAG, "External automation rejected because the user setting is disabled")
            ExternalAutomationContract.sendCallback(
                context = context,
                returnAction = request.returnAction,
                requestId = request.requestId,
                status = ExternalAutomationContract.STATUS_REJECTED,
                error = "External Automation is disabled in PokeClaw Settings.",
                returnPackage = request.returnPackage,
                mode = request.mode,
            )
            return false
        }

        if (appViewModel.isTaskRunning()) {
            ExternalAutomationContract.sendCallback(
                context = context,
                returnAction = request.returnAction,
                requestId = request.requestId,
                status = ExternalAutomationContract.STATUS_REJECTED,
                error = "Another PokeClaw task is already running.",
                returnPackage = request.returnPackage,
                mode = request.mode,
            )
            return false
        }

        XLog.i(TAG, "Accepted external automation ${request.mode}: ${request.text.take(MAX_LOG_TEXT)}")
        ExternalAutomationContract.sendCallback(
            context = context,
            returnAction = request.returnAction,
            requestId = request.requestId,
            status = ExternalAutomationContract.STATUS_ACCEPTED,
            returnPackage = request.returnPackage,
            mode = request.mode,
        )

        val launch = Intent(context, ComposeChatActivity::class.java).apply {
            when (request.mode) {
                ExternalAutomationContract.Mode.TASK -> putExtra(EXTRA_TASK, request.text)
                ExternalAutomationContract.Mode.CHAT -> putExtra(EXTRA_CHAT, request.text)
            }
            putExtra(EXTRA_EXTERNAL_REQUEST_ID, request.requestId)
            putExtra(EXTRA_EXTERNAL_RETURN_ACTION, request.returnAction)
            putExtra(EXTRA_EXTERNAL_RETURN_PACKAGE, request.returnPackage)
            flags = launchFlags
        }
        context.startActivity(launch)
        return true
    }

    private fun isTargetedToPokeClaw(context: Context, intent: Intent): Boolean {
        val packageName = context.packageName
        val component = intent.component
        return component?.packageName == packageName || intent.`package` == packageName
    }

    private fun rejectFromIntent(context: Context, intent: Intent, error: String) {
        ExternalAutomationContract.sendCallback(
            context = context,
            returnAction = intent.getStringExtra(ExternalAutomationContract.EXTRA_RETURN_ACTION),
            requestId = intent.getStringExtra(ExternalAutomationContract.EXTRA_REQUEST_ID),
            status = ExternalAutomationContract.STATUS_REJECTED,
            error = error,
            returnPackage = intent.getStringExtra(ExternalAutomationContract.EXTRA_RETURN_PACKAGE),
        )
    }

    const val EXTRA_TASK = "task"
    const val EXTRA_CHAT = "chat"
    const val EXTRA_EXTERNAL_REQUEST_ID = "external_request_id"
    const val EXTRA_EXTERNAL_RETURN_ACTION = "external_return_action"
    const val EXTRA_EXTERNAL_RETURN_PACKAGE = "external_return_package"
}
