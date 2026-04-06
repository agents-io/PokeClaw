package io.agents.pokeclaw.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.agents.pokeclaw.appViewModel
import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.utils.XLog

/**
 * Debug-only broadcast receiver for triggering tasks via ADB without UI interaction.
 *
 * Usage: adb shell am broadcast -a io.agents.pokeclaw.DEBUG_TASK --es task "open my camera"
 */
class DebugTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val task = intent.getStringExtra("task") ?: "open my camera"
        XLog.i("DebugTaskReceiver", "Received debug task: $task")

        try {
            val vm = io.agents.pokeclaw.ClawApplication.appViewModelInstance
            vm.startNewTask(Channel.LOCAL, task, "debug_${System.currentTimeMillis()}")
            XLog.i("DebugTaskReceiver", "Task started: $task")
        } catch (e: Exception) {
            XLog.e("DebugTaskReceiver", "Failed to start task", e)
        }
    }
}
