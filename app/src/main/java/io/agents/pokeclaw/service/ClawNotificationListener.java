// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.service;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.app.Notification;
import android.os.Bundle;

import io.agents.pokeclaw.utils.XLog;

import java.util.Set;
import java.util.HashSet;

/**
 * Listens for ALL notifications (including updates to existing ones).
 * Routes messaging notifications to AutoReplyManager.
 *
 * Unlike AccessibilityService's TYPE_NOTIFICATION_STATE_CHANGED, this fires
 * reliably on notification updates — fixing the bug where WhatsApp updates
 * an existing notification instead of creating a new one.
 *
 * Also provides cancelNotification() to dismiss notifications after replying,
 * ensuring the next message triggers a fresh notification event.
 *
 * Requires: Settings → Notification Access → PokeClaw enabled.
 */
public class ClawNotificationListener extends NotificationListenerService {

    private static final String TAG = "ClawNotifListener";
    private static ClawNotificationListener instance;

    private static final Set<String> MESSAGING_APPS = new HashSet<>();
    static {
        MESSAGING_APPS.add("com.whatsapp");
        MESSAGING_APPS.add("org.telegram.messenger");
        MESSAGING_APPS.add("com.google.android.apps.messaging");
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        XLog.i(TAG, "Notification listener connected");
        // Auto-return to app Settings page so user can see the updated status
        try {
            android.content.Intent intent = new android.content.Intent(this, io.agents.pokeclaw.ui.settings.SettingsActivity.class);
            intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (Exception e) {
            XLog.w(TAG, "Could not bring app to foreground after listener connected", e);
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        instance = null;
        XLog.i(TAG, "Notification listener disconnected");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (!MESSAGING_APPS.contains(pkg)) return;

        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null) return;

        Bundle extras = notification.extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "");
        String text = extras.getString(Notification.EXTRA_TEXT, "");

        if (title.isEmpty() || text.isEmpty()) return;

        XLog.d(TAG, "Notification from " + pkg + ": title='" + title + "' text='" + text + "'");

        // Route to AutoReplyManager
        AutoReplyManager.getInstance().onNotificationReceived(pkg, title, text);
    }

    /**
     * Dismiss all notifications from a specific package.
     * Called after replying so the next message triggers a fresh notification.
     */
    public static void dismissNotifications(String packageName) {
        ClawNotificationListener listener = instance;
        if (listener == null) {
            XLog.w(TAG, "Cannot dismiss notifications — listener not connected");
            return;
        }
        try {
            StatusBarNotification[] active = listener.getActiveNotifications();
            if (active == null) return;
            int dismissed = 0;
            for (StatusBarNotification sbn : active) {
                if (sbn.getPackageName().equals(packageName)) {
                    listener.cancelNotification(sbn.getKey());
                    dismissed++;
                }
            }
            XLog.i(TAG, "Dismissed " + dismissed + " notifications from " + packageName);
        } catch (Exception e) {
            XLog.w(TAG, "Failed to dismiss notifications", e);
        }
    }

    public static boolean isConnected() {
        return instance != null;
    }
}
