// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.service;

import android.content.ComponentName;
import android.content.Context;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.app.Notification;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import io.agents.pokeclaw.utils.KVUtils;
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
        MESSAGING_APPS.add("jp.naver.line.android");
        MESSAGING_APPS.add("com.tencent.mm");
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        KVUtils.INSTANCE.noteNotificationListenerConnected();
        XLog.i(TAG, "Notification listener connected");
        ForegroundService.Companion.syncToBackgroundState(this);
        maybeReturnToAppAfterPermissionFlow();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        instance = null;
        KVUtils.INSTANCE.noteNotificationListenerDisconnected();
        XLog.i(TAG, "Notification listener disconnected");
        ForegroundService.Companion.syncToBackgroundState(this);
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

    public static boolean isEnabledInSettings(Context context) {
        try {
            String enabledListeners = Settings.Secure.getString(
                    context.getContentResolver(),
                    "enabled_notification_listeners");
            if (enabledListeners == null || enabledListeners.isEmpty()) return false;
            String myListener = new ComponentName(context, ClawNotificationListener.class).flattenToString();
            return enabledListeners.contains(myListener);
        } catch (Exception e) {
            XLog.e(TAG, "Failed to check notification listener settings", e);
            return false;
        }
    }

    public static boolean awaitConnected(long timeoutMs) {
        if (instance != null) return true;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (instance != null) return true;
        }
        return false;
    }

    /**
     * Get all active notifications. Used by GetNotificationsTool.
     * Returns null if listener is not connected.
     */
    public static StatusBarNotification[] getActiveNotificationList() {
        ClawNotificationListener listener = instance;
        if (listener == null) return null;
        try {
            return listener.getActiveNotifications();
        } catch (Exception e) {
            XLog.w(TAG, "Failed to get active notifications", e);
            return null;
        }
    }

    private void maybeReturnToAppAfterPermissionFlow() {
        boolean pendingReturn;
        try {
            pendingReturn = KVUtils.INSTANCE.consumePendingNotificationAccessReturn(120_000L);
        } catch (Exception e) {
            XLog.w(TAG, "Failed to read pending notification access return flag", e);
            return;
        }
        if (!pendingReturn) {
            return;
        }

        XLog.i(TAG, "Completing pending notification access return");
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.postDelayed(() -> {
            try {
                android.content.Intent intent = new android.content.Intent(this, io.agents.pokeclaw.ui.settings.SettingsActivity.class);
                intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            } catch (Exception e) {
                XLog.w(TAG, "Could not bring app to foreground after listener connected", e);
            }
        }, 400);
    }
}
