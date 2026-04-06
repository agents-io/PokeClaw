// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import io.agents.pokeclaw.ui.chat.ComposeChatActivity;
import io.agents.pokeclaw.utils.XLog;

/**
 * Debug broadcast receiver to trigger tasks via ADB without manual UI interaction.
 *
 * Usage:
 *   adb shell am broadcast -a io.agents.pokeclaw.TASK --es task "send hi to Mom on WhatsApp" -p io.agents.pokeclaw
 *
 * Launches ComposeChatActivity with the task extra — works even after reinstall.
 */
public class TaskTriggerReceiver extends BroadcastReceiver {

    private static final String TAG = "TaskTriggerReceiver";
    public static final String ACTION = "io.agents.pokeclaw.TASK";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        String task = intent.getStringExtra("task");
        if (task == null || task.isEmpty()) {
            XLog.w(TAG, "Received broadcast with no task extra");
            return;
        }
        XLog.i(TAG, "Received task via broadcast: " + task);

        // Auto-reply commands: "autoreply on Mom" / "autoreply off"
        if (task.startsWith("autoreply ")) {
            String cmd = task.substring(10).trim();
            if (cmd.equals("off")) {
                io.agents.pokeclaw.service.AutoReplyManager.getInstance().setEnabled(false);
                XLog.i(TAG, "Auto-reply disabled");
            } else if (cmd.startsWith("on ")) {
                String contact = cmd.substring(3).trim();
                io.agents.pokeclaw.service.AutoReplyManager.getInstance().addContact(contact);
                io.agents.pokeclaw.service.AutoReplyManager.getInstance().setEnabled(true);
                XLog.i(TAG, "Auto-reply enabled for: " + contact);
            }
            return;
        }

        // Launch ComposeChatActivity with task extra — this always works
        Intent launch = new Intent(context, ComposeChatActivity.class);
        launch.putExtra("task", task);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(launch);
    }
}
