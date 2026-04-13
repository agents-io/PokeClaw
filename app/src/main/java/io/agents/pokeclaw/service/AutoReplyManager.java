// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.service;

import android.app.KeyguardManager;
import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import io.agents.pokeclaw.AppCapabilityCoordinator;
import io.agents.pokeclaw.tool.ToolRegistry;
import io.agents.pokeclaw.tool.ToolResult;
import io.agents.pokeclaw.tool.impl.SendMessageTool;
import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.ServiceBindingState;
import io.agents.pokeclaw.utils.ChatNoiseFilterUtils;
import io.agents.pokeclaw.utils.ContactListUiUtils;
import io.agents.pokeclaw.utils.ContactMatchUtils;
import io.agents.pokeclaw.utils.UiActionMatchUtils;
import io.agents.pokeclaw.utils.XLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Monitors incoming WhatsApp/Telegram notifications and auto-replies.
 *
 * Usage:
 *   AutoReplyManager.getInstance().setEnabled(true);
 *   AutoReplyManager.getInstance().addTarget("Mom", "WhatsApp");
 *
 * When enabled, intercepts notifications from messaging apps,
 * extracts sender + message, generates a reply via on-device LLM,
 * and sends it via SendMessageTool.
 */
public class AutoReplyManager {

    private enum AutoReplyFailureStage {
        ACCESSIBILITY_UNAVAILABLE,
        SCREEN_LOCKED,
        CHAT_NAVIGATION_FAILED,
        CONTEXT_EMPTY,
        GENERATION_FAILED,
        SEND_FAILED,
    }

    public static final class MonitorTarget {
        private final String displayName;
        private final String appName;
        private final String packageName;
        private final String key;
        private final LinkedHashSet<String> normalizedAliases;
        private final LinkedHashSet<String> digitAliases;

        private MonitorTarget(String displayName, String appName, String packageName) {
            this.displayName = displayName;
            this.appName = appName;
            this.packageName = packageName;
            this.key = packageName + "|" + normalizeNameStatic(displayName) + "|" + digitsOnly(displayName);
            this.normalizedAliases = ContactMatchUtils.buildNormalizedAliases(displayName);
            this.digitAliases = ContactMatchUtils.buildDigitAliases(displayName);
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getAppName() {
            return appName;
        }

        public String getPackageName() {
            return packageName;
        }

        public String getDisplayLabel() {
            return displayName + " on " + appName;
        }

        public String getKey() {
            return key;
        }

        private boolean matches(String incomingPackage, String incomingTitle) {
            if (!packageName.equals(incomingPackage)) return false;
            return ContactMatchUtils.matchesCandidate(incomingTitle, normalizedAliases, digitAliases);
        }

        private boolean matchesRemovalQuery(String query) {
            return ContactMatchUtils.matchesCandidate(query, normalizedAliases, digitAliases) ||
                ContactMatchUtils.matchesCandidate(query, ContactMatchUtils.buildNormalizedAliases(getDisplayLabel()), ContactMatchUtils.buildDigitAliases(getDisplayLabel())) ||
                ContactMatchUtils.matchesCandidate(query, ContactMatchUtils.buildNormalizedAliases(appName + " " + displayName), ContactMatchUtils.buildDigitAliases(appName + " " + displayName));
        }
    }

    private static final String TAG = "AutoReplyManager";
    private static AutoReplyManager instance;

    private boolean enabled = false;
    private final Map<String, MonitorTarget> monitoredTargets = new LinkedHashMap<>();
    private final Set<String> monitoredApps = new LinkedHashSet<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean replying = new AtomicBoolean(false);

    // Debounce: don't reply to same contact within 5s (avoid loops).
    // ownSentMessages handles self-reply detection, so debounce can be short.
    private final Map<String, Long> lastReplyTime = new HashMap<>();
    private static final long DEBOUNCE_MS = 5_000;

    // Track our own sent messages to avoid replying to ourselves
    private final Set<String> ownSentMessages = new HashSet<>();

    // When messages arrive while replying, flag it.
    // After current reply finishes, re-open chat, read everything, reply to latest.
    private volatile boolean hasPending = false;
    private volatile String pendingPackage = null;
    private volatile String pendingContact = null;



    // For content change detection (chatroom open)
    private long lastContentChangeCheck = 0;
    private String lastProcessedFingerprint = "";

    private AutoReplyManager() {
        // Default monitored apps
        monitoredApps.add("com.whatsapp");
        monitoredApps.add("org.telegram.messenger");
        monitoredApps.add("com.google.android.apps.messaging");
        monitoredApps.add("jp.naver.line.android");
        monitoredApps.add("com.tencent.mm");
    }

    public static synchronized AutoReplyManager getInstance() {
        if (instance == null) instance = new AutoReplyManager();
        return instance;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        XLog.i(TAG, "Auto-reply " + (enabled ? "ENABLED" : "DISABLED") +
                " for contacts: " + getMonitoredContacts());
        syncForegroundNotification();
    }

    public boolean isEnabled() { return enabled; }

    public Set<String> getMonitoredContacts() {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        for (MonitorTarget target : monitoredTargets.values()) {
            labels.add(target.getDisplayLabel());
        }
        return labels;
    }

    public List<MonitorTarget> getMonitoredTargets() {
        return new ArrayList<>(monitoredTargets.values());
    }

    public void stopAll() {
        enabled = false;
        monitoredTargets.clear();
        XLog.i(TAG, "Auto-reply stopped and all contacts cleared");
        syncForegroundNotification();
    }

    public void addContact(String name) {
        addTarget(name, "WhatsApp");
    }

    public void addTarget(String name, String appName) {
        MonitorTarget target = buildTarget(name, appName);
        if (target == null) {
            XLog.w(TAG, "Ignoring empty monitor target");
            return;
        }
        monitoredTargets.put(target.getKey(), target);
        XLog.i(TAG, "Added monitor target: " + target.getDisplayLabel());
        syncForegroundNotification();
    }

    public void removeContact(String name) {
        String query = name != null ? name.trim() : "";
        if (query.isEmpty()) return;

        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, MonitorTarget> entry : monitoredTargets.entrySet()) {
            if (entry.getValue().matchesRemovalQuery(query)) {
                toRemove.add(entry.getKey());
            }
        }
        for (String key : toRemove) {
            monitoredTargets.remove(key);
        }
        syncForegroundNotification();
    }

    /**
     * Debug-only helper so we can exercise the exact same auto-reply pipeline
     * without needing a second physical sender device.
     */
    public void debugSimulateIncomingMessage(String appName, String sender, String message, boolean ensureTargetEnabled) {
        String normalizedApp = normalizeAppName(appName);
        String packageName = resolvePackageName(normalizedApp);
        if (packageName == null) {
            normalizedApp = "WhatsApp";
            packageName = "com.whatsapp";
        }

        if (ensureTargetEnabled) {
            addTarget(sender, normalizedApp);
            setEnabled(true);
        }

        XLog.i(TAG, "Debug-simulating incoming message from " + sender + " on " + normalizedApp + ": '" + message + "'");
        onNotificationReceived(packageName, sender, message);
    }

    public void clearContacts() {
        monitoredTargets.clear();
        syncForegroundNotification();
    }

    private void syncForegroundNotification() {
        try {
            if (enabled && !monitoredTargets.isEmpty()) {
                ForegroundService.Companion.showMonitorStatus(ClawApplication.Companion.getInstance());
            } else {
                ForegroundService.Companion.resetToIdle(ClawApplication.Companion.getInstance());
            }
        } catch (Exception e) {
            XLog.w(TAG, "Failed to sync foreground notification", e);
        }
    }

    /**
     * Called from ClawNotificationListener (primary, reliable) or
     * ClawAccessibilityService (fallback if NotificationListener not enabled).
     */
    public void onNotificationReceived(String packageName, String title, String text) {
        if (!enabled) return;
        if (!monitoredApps.contains(packageName)) return;
        if (title.isEmpty() || text.isEmpty()) return;
        if (!isAccessibilityHealthyForReply()) return;

        XLog.d(TAG, "Notification from " + packageName + ": title='" + title + "' text='" + text + "'");
        handleIncomingMessage(packageName, title, text);
    }

    /**
     * Fallback: Called from ClawAccessibilityService.onAccessibilityEvent.
     * Only used if NotificationListenerService is not connected.
     */
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!enabled) return;
        if (!isAccessibilityHealthyForReply()) return;

        // Skip if NotificationListenerService is active — it's more reliable
        if (ClawNotificationListener.isConnected()) return;

        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!monitoredApps.contains(packageName)) return;

        if (event.getEventType() != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return;

        android.os.Parcelable parcelable = event.getParcelableData();
        if (!(parcelable instanceof Notification)) return;
        Notification notification = (Notification) parcelable;
        Bundle extras = notification.extras;
        if (extras == null) return;
        String title = extras.getString(Notification.EXTRA_TITLE, "");
        String text = extras.getString(Notification.EXTRA_TEXT, "");

        if (title.isEmpty() || text.isEmpty()) return;

        XLog.d(TAG, "[Fallback] Notification from " + packageName + ": title='" + title + "' text='" + text + "'");
        handleIncomingMessage(packageName, title, text);
    }

    private boolean isAccessibilityHealthyForReply() {
        ClawApplication app = ClawApplication.Companion.getInstance();
        if (app == null) return false;
        ServiceBindingState state = AppCapabilityCoordinator.INSTANCE.accessibilityState(app);
        if (state == ServiceBindingState.READY || state == ServiceBindingState.CONNECTING) {
            return true;
        }
        XLog.w(TAG, "Skipping auto-reply because accessibility is " + state);
        syncForegroundNotification();
        return false;
    }

    private boolean ensureDeviceReadyForReply(ClawAccessibilityService svc, MonitorTarget target, String incomingMessage) {
        ClawApplication app = ClawApplication.Companion.getInstance();
        if (app == null) {
            failAutoReply(target, incomingMessage, AutoReplyFailureStage.SCREEN_LOCKED,
                "Application context unavailable while preparing auto-reply", null);
            return false;
        }

        PowerManager powerManager = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        KeyguardManager keyguardManager = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
        boolean interactive = powerManager == null || powerManager.isInteractive();
        boolean keyguardLocked = keyguardManager != null && keyguardManager.isKeyguardLocked();

        logAutoReplyStep(target, "device-state", "interactive=" + interactive + ", keyguardLocked=" + keyguardLocked);
        if (interactive && !keyguardLocked) {
            return true;
        }

        logAutoReplyStep(target, "unlock-attempt", "requesting unlock before auto-reply");
        boolean unlockRequested = svc.unlockScreen();
        if (!unlockRequested) {
            failAutoReply(target, incomingMessage, AutoReplyFailureStage.SCREEN_LOCKED,
                "unlockScreen request was rejected", null);
            return false;
        }

        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failAutoReply(target, incomingMessage, AutoReplyFailureStage.SCREEN_LOCKED,
                "Interrupted while waiting for screen unlock", e);
            return false;
        }

        boolean interactiveAfter = powerManager == null || powerManager.isInteractive();
        boolean keyguardLockedAfter = keyguardManager != null && keyguardManager.isKeyguardLocked();
        logAutoReplyStep(target, "device-state-after-unlock", "interactive=" + interactiveAfter + ", keyguardLocked=" + keyguardLockedAfter);
        if (!interactiveAfter || keyguardLockedAfter) {
            failAutoReply(target, incomingMessage, AutoReplyFailureStage.SCREEN_LOCKED,
                "Device remained locked after unlock attempt", null);
            return false;
        }
        return true;
    }

    private void handleIncomingMessage(String packageName, String title, String text) {

        // Check if sender belongs to one of our monitored targets for this app
        MonitorTarget matchedTarget = null;
        for (MonitorTarget target : monitoredTargets.values()) {
            if (target.matches(packageName, title)) {
                matchedTarget = target;
                break;
            }
        }
        if (matchedTarget == null) return;

        // Skip summary notifications like "2 new messages", "3 messages from Mom"
        if (text.matches("^\\d+ (new )?messages?.*")) {
            XLog.d(TAG, "Skipping summary notification: " + text);
            return;
        }

        // Skip our own sent messages
        if (ownSentMessages.remove(text)) {
            XLog.d(TAG, "Skipping own message: " + text);
            return;
        }

        // Debounce — don't reply to same contact within 30s
        long now = System.currentTimeMillis();
        Long lastReply = lastReplyTime.get(matchedTarget.getKey());
        if (lastReply != null && (now - lastReply) < DEBOUNCE_MS) {
            XLog.d(TAG, "Debounce: skipping reply to " + matchedTarget.getDisplayLabel() + " (replied " + ((now - lastReply) / 1000) + "s ago)");
            return;
        }

        // If already replying, just flag it — after current reply we'll re-open chat,
        // read the full screen (all messages including new ones), and reply to latest.
        if (!replying.compareAndSet(false, true)) {
            hasPending = true;
            pendingPackage = packageName;
            pendingContact = matchedTarget.getDisplayName();
            XLog.d(TAG, "Already replying, flagged pending from " + matchedTarget.getDisplayLabel());
            return;
        }

        final MonitorTarget finalTarget = matchedTarget;
        String finalContact = finalTarget.getDisplayName();
        String incomingMessage = text;
        String appName = finalTarget.getAppName();

        XLog.i(TAG, "Auto-replying to " + finalTarget.getDisplayLabel() + ": '" + incomingMessage + "'");

        executor.submit(() -> {
            try {
                logAutoReplyStep(finalTarget, "start", "incoming='" + incomingMessage + "'");
                // Open the messaging app and navigate to the contact's chat
                ClawAccessibilityService svc = ClawAccessibilityService.getConnectedInstance(12000L);
                if (svc == null) {
                    failAutoReply(finalTarget, incomingMessage, AutoReplyFailureStage.ACCESSIBILITY_UNAVAILABLE,
                        "No accessibility service, cannot open chat", null);
                    return;
                }

                if (!ensureDeviceReadyForReply(svc, finalTarget, incomingMessage)) {
                    return;
                }

                logAutoReplyStep(finalTarget, "open-app", packageName);
                svc.openApp(packageName);
                Thread.sleep(2000);

                // Navigate to the contact's chatroom
                AccessibilityNodeInfo root = svc.getRootInActiveWindow();
                boolean navigatedToChat = false;
                if (root != null) {
                    LinkedHashSet<String> normalizedAliases = ContactMatchUtils.buildNormalizedAliases(finalContact);
                    LinkedHashSet<String> digitAliases = ContactMatchUtils.buildDigitAliases(finalContact);
                    // Check if already in the right chatroom:
                    // 1) contact name in toolbar AND 2) message input field exists
                    boolean inChat = false;
                    boolean hasContactInToolbar = false;
                    List<AccessibilityNodeInfo> topNodes = new ArrayList<>();
                    collectTopBarNodes(root, topNodes);
                    for (AccessibilityNodeInfo node : topNodes) {
                        if (ContactMatchUtils.matchesTarget(node.getText(), node.getContentDescription(), normalizedAliases, digitAliases)) {
                            hasContactInToolbar = true;
                            break;
                        }
                    }
                    if (hasContactInToolbar) {
                        // Also check for EditText (message input) — contact info page has
                        // the name in toolbar too but no EditText
                        List<AccessibilityNodeInfo> editTexts = new ArrayList<>();
                        collectEditTexts(root, editTexts);
                        inChat = !editTexts.isEmpty();
                        if (!inChat) {
                            // We're on contact info or similar — press back to get to chat
                            XLog.i(TAG, "Contact name in toolbar but no input field — pressing back");
                            svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                            Thread.sleep(1500);
                            root = svc.getRootInActiveWindow();
                            // Re-check
                            if (root != null) {
                                editTexts.clear();
                                collectEditTexts(root, editTexts);
                                inChat = !editTexts.isEmpty();
                            }
                        }
                    }

                    if (!inChat) {
                        // Not in chat — go back to chat list, find contact, tap
                        logAutoReplyStep(finalTarget, "navigate-chat", "not already in chat, reopening chat list");
                        if (ContactListUiUtils.prepareForContactLookup(svc, packageName, 4, 1200)) {
                            boolean clicked = ContactListUiUtils.searchOrScrollAndFindAndClick(svc, finalContact, normalizedAliases, digitAliases, 12, 800);
                            if (clicked) {
                                XLog.i(TAG, "Tapped contact: " + finalContact);
                                Thread.sleep(2000);
                                navigatedToChat = waitForChatroom(svc, finalContact);
                                logAutoReplyStep(finalTarget, "navigate-chat-result", "clicked=true, verified=" + navigatedToChat);
                            } else {
                                failAutoReply(finalTarget, incomingMessage, AutoReplyFailureStage.CHAT_NAVIGATION_FAILED,
                                    "Could not find " + finalContact + " in chat list", null);
                                return;
                            }
                        } else {
                            failAutoReply(finalTarget, incomingMessage, AutoReplyFailureStage.CHAT_NAVIGATION_FAILED,
                                "Could not return to a searchable chat list", null);
                            return;
                        }
                    } else {
                        XLog.i(TAG, "Already in " + finalContact + "'s chat");
                        navigatedToChat = true;
                    }
                }

                if (!navigatedToChat && !waitForChatroom(svc, finalContact)) {
                    failAutoReply(finalTarget, incomingMessage, AutoReplyFailureStage.CHAT_NAVIGATION_FAILED,
                        "Chatroom not verified before context scrape", null);
                    return;
                }

                // NOW read conversation context — chat is visible on screen
                String context = readConversationContext();
                XLog.i(TAG, "Context before reply: " + (context.isEmpty() ? "(empty)" : context.length() + " chars"));
                logAutoReplyStep(finalTarget, "context-scrape", context.isEmpty() ? "empty" : ("chars=" + context.length()));

                if (context.isEmpty() && isSyntheticPendingMessage(incomingMessage)) {
                    failAutoReply(finalTarget, incomingMessage, AutoReplyFailureStage.CONTEXT_EMPTY,
                        "Pending replay had no readable context; skipping unsafe auto-reply", null);
                    return;
                }

                // Generate reply with full context
                String reply = generateReply(finalContact, incomingMessage, context);
                if (reply == null || reply.trim().isEmpty()) {
                    failAutoReply(finalTarget, incomingMessage, AutoReplyFailureStage.GENERATION_FAILED,
                        "LLM did not produce a safe auto-reply", null);
                    return;
                }
                logAutoReplyStep(finalTarget, "generation", "reply='" + reply + "'");

                // Track our own message to avoid loop
                ownSentMessages.add(reply);
                boolean ownMessageTracked = true;

                // We're already in the chatroom from the navigate step above
                ToolResult result;

                if (svc != null && isInChatroom(svc, finalContact)) {
                    // Fast path: already in chatroom, just type + send
                    XLog.i(TAG, "Fast path: already in chatroom, typing directly");
                    logAutoReplyStep(finalTarget, "send-path", "fast");
                    result = typeAndSendInOpenChat(svc, reply);
                } else {
                    // Full path: open app, find contact, type, send
                    logAutoReplyStep(finalTarget, "send-path", "tool");
                    SendMessageTool sendTool = new SendMessageTool();
                    Map<String, Object> params = new HashMap<>();
                    params.put("contact", finalContact);
                    params.put("message", reply);
                    params.put("app", appName);
                    result = sendTool.execute(params);
                }

                if (result.isSuccess()) {
                    XLog.i(TAG, "Auto-reply sent to " + finalContact + ": '" + reply + "'");
                    lastReplyTime.put(finalTarget.getKey(), System.currentTimeMillis());
                    logAutoReplyStep(finalTarget, "send-success", reply);

                    // Dismiss the messaging app's notifications so the next message
                    // triggers a fresh notification event (not an update to existing one).
                    ClawNotificationListener.dismissNotifications(packageName);

                    // Stay in the current app. NotificationListener continues to receive
                    // future messages without forcing the user back to Home.
                    XLog.i(TAG, "Auto-reply sent; staying in current app");
                } else {
                    if (ownMessageTracked) {
                        ownSentMessages.remove(reply);
                    }
                    failAutoReply(finalTarget, incomingMessage, AutoReplyFailureStage.SEND_FAILED,
                        "Send step failed: " + result.getError(), null);
                }
            } catch (Exception e) {
                failAutoReply(finalTarget, incomingMessage, AutoReplyFailureStage.SEND_FAILED,
                    "Auto-reply pipeline crashed", e);
            } finally {
                replying.set(false);
                lastProcessedFingerprint = "";

                // If messages arrived while we were replying, re-open chat and reply
                if (hasPending) {
                    String pPkg = pendingPackage;
                    String pContact = pendingContact;
                    hasPending = false;
                    pendingPackage = null;
                    pendingContact = null;

                    if (pPkg != null && pContact != null) {
                        XLog.i(TAG, "Pending messages from " + pContact + " — re-reading chat");
                        // Use a dummy text — the actual reply will be based on
                        // reading the full screen, not this text
                        handleIncomingMessage(pPkg, pContact, "(pending)");
                    }
                }
            }
        });
    }

    private MonitorTarget buildTarget(String name, String appName) {
        String displayName = name != null ? name.trim() : "";
        if (displayName.isEmpty()) return null;
        String resolvedAppName = normalizeAppName(appName);
        String packageName = resolvePackageName(resolvedAppName);
        if (packageName == null) {
            resolvedAppName = "WhatsApp";
            packageName = "com.whatsapp";
        }
        return new MonitorTarget(displayName, resolvedAppName, packageName);
    }

    private String normalizeName(String name) {
        return normalizeNameStatic(name);
    }

    private static String normalizeNameStatic(String name) {
        return ContactMatchUtils.normalizeText(name);
    }

    private static String digitsOnly(String value) {
        return ContactMatchUtils.digitsOnly(value);
    }

    /**
     * Generate a reply using on-device LLM.
     * Returns null if a safe reply cannot be generated.
     */
    private static final String REPLY_SYSTEM_PROMPT =
        "You are replying to a chat message on behalf of the user. " +
        "Keep replies SHORT (under 15 words), casual, and friendly. " +
        "Reply in the same language as the incoming message. " +
        "Do NOT use emojis excessively. Sound like a real person texting.";

    private String generateReply(String sender, String incomingMessage, String conversationContext) {
        try {
            String prompt;
            if (conversationContext != null && !conversationContext.isEmpty()) {
                prompt = "Recent conversation:\n" + conversationContext + "\n" +
                         "Read the entire conversation above. Reply to ALL of " + sender + "'s messages that you (me) haven't responded to yet. Address every point in one reply. Your reply:";
            } else {
                prompt = sender + " says: \"" + incomingMessage + "\"\nYour reply:";
            }

            String provider = io.agents.pokeclaw.utils.KVUtils.INSTANCE.getLlmProvider();
            String reply;

            if (!"LOCAL".equals(provider)) {
                // Cloud LLM path — use the user's selected cloud provider
                reply = generateReplyCloud(prompt);
            } else {
                // Local LLM path — use on-device LiteRT-LM
                reply = generateReplyLocal(prompt);
            }

            if (reply == null || reply.isEmpty() || reply.length() > 200) {
                XLog.w(TAG, "generateReply: LLM reply invalid, skipping auto-reply");
                return null;
            }

            XLog.i(TAG, "generateReply: LLM generated '" + reply + "' for '" + incomingMessage + "' (provider=" + provider + ")");
            return reply;

        } catch (Exception e) {
            XLog.w(TAG, "generateReply: LLM failed, skipping auto-reply", e);
            return null;
        }
    }

    private String generateReplyCloud(String prompt) {
        XLog.i(TAG, "generateReplyCloud: using LlmSessionManager");
        String reply = io.agents.pokeclaw.agent.llm.LlmSessionManager.INSTANCE.singleShotCloud(
            REPLY_SYSTEM_PROMPT, prompt, 0.7
        );
        if (reply != null) {
            reply = reply.replaceAll("^[\"']|[\"']$", "").trim();
            if (reply.startsWith("Your reply:")) reply = reply.substring(11).trim();
        }
        return reply;
    }

    private String generateReplyLocal(String prompt) {
        XLog.i(TAG, "generateReplyLocal: using LlmSessionManager");
        String reply = io.agents.pokeclaw.agent.llm.LlmSessionManager.INSTANCE.singleShotLocal(
            REPLY_SYSTEM_PROMPT, prompt, 0.7
        );
        if (reply != null) {
            reply = reply.replaceAll("^[\"']|[\"']$", "").trim();
            if (reply.startsWith("Your reply:")) reply = reply.substring(11).trim();
        }
        return reply;
    }

    /**
     * Find contact name from the messaging app toolbar.
     * Works generically — looks for a TextView near the top of screen.
     */
    private String findContactNameInToolbar(AccessibilityNodeInfo root) {
        // Look for clickable container near top with a text child (contact name)
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        collectTextNodesInRegion(root, 0, 300, candidates); // top 300px = toolbar area

        android.graphics.Rect rootBounds = new android.graphics.Rect();
        root.getBoundsInScreen(rootBounds);
        for (AccessibilityNodeInfo node : candidates) {
            CharSequence text = node.getText();
            if (text != null && text.length() > 0 && text.length() < 30) {
                String t = text.toString();
                android.graphics.Rect bounds = new android.graphics.Rect();
                node.getBoundsInScreen(bounds);
                if (ChatNoiseFilterUtils.isLikelyTimestampLike(t)) continue;
                if (ChatNoiseFilterUtils.isLikelyCenteredSystemLabel(bounds, rootBounds, t)) continue;
                return t;
            }
        }
        return "";
    }

    /**
     * Find the last incoming message in the chat.
     * Incoming messages are typically LEFT-aligned (x < screen midpoint).
     */
    private String findLastIncomingMessage(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> allText = new ArrayList<>();
        collectAllTextNodes(root, allText);

        // Screen midpoint — incoming messages are on the left
        android.graphics.Rect rootBounds = new android.graphics.Rect();
        root.getBoundsInScreen(rootBounds);
        int midX = rootBounds.centerX();
        String lastIncoming = "";

        for (AccessibilityNodeInfo node : allText) {
            CharSequence text = node.getText();
            if (text == null || text.length() == 0 || text.length() > 200) continue;

            android.graphics.Rect bounds = new android.graphics.Rect();
            node.getBoundsInScreen(bounds);

            // Skip toolbar area (top) and input area (bottom 250px)
            if (bounds.top < 300 || bounds.top > rootBounds.height() - 250) continue;

            // Left-aligned = incoming (not our own message)
            if (bounds.centerX() < midX) {
                String msg = text.toString();
                if (ChatNoiseFilterUtils.isLikelyNonMessageLabel(bounds, rootBounds, msg)) continue;
                lastIncoming = msg;
            }
        }
        return lastIncoming;
    }

    private void collectTextNodesInRegion(AccessibilityNodeInfo node, int minY, int maxY, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.top >= minY && bounds.bottom <= maxY && node.getText() != null) {
            result.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectTextNodesInRegion(child, minY, maxY, result);
        }
    }

    /**
     * Check if we're already in a chatroom with the given contact.
     */
    private boolean isInChatroom(ClawAccessibilityService service, String contact) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;
        CharSequence pkg = root.getPackageName();
        if (pkg == null || !monitoredApps.contains(pkg.toString())) return false;
        String toolbarName = findContactNameInToolbar(root);
        LinkedHashSet<String> normalizedAliases = ContactMatchUtils.buildNormalizedAliases(contact);
        LinkedHashSet<String> digitAliases = ContactMatchUtils.buildDigitAliases(contact);
        return ContactMatchUtils.matchesCandidate(
            toolbarName,
            normalizedAliases,
            digitAliases
        );
    }

    private boolean waitForChatroom(ClawAccessibilityService service, String contact) {
        for (int attempt = 1; attempt <= 4; attempt++) {
            if (isInChatroom(service, contact)) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean isSyntheticPendingMessage(String incomingMessage) {
        return "(pending)".equalsIgnoreCase(incomingMessage != null ? incomingMessage.trim() : "");
    }

    private void logAutoReplyStep(MonitorTarget target, String step, String detail) {
        XLog.i(TAG, "[trace] " + target.getDisplayLabel() + " :: " + step + " :: " + detail);
    }

    private void failAutoReply(MonitorTarget target, String incomingMessage, AutoReplyFailureStage stage, String detail, Exception error) {
        String message = "[trace] " + target.getDisplayLabel() + " :: fail@" + stage.name() + " :: " + detail;
        if (error == null) {
            XLog.w(TAG, message + " | incoming='" + incomingMessage + "'");
        } else {
            XLog.w(TAG, message + " | incoming='" + incomingMessage + "'", error);
        }
    }

    /**
     * Type and send a message when already inside the chatroom.
     *
     * Strategy: try generic Java path first (fast, works 90% of the time),
     * fall back to LLM-assisted UI interaction if Java path fails.
     * This makes it app-agnostic — no per-app code needed.
     */
    private ToolResult typeAndSendInOpenChat(ClawAccessibilityService service, String message) {
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) return ToolResult.error("No active window");

            // Step 1: Find input field — generic (bottom-most EditText)
            java.util.List<AccessibilityNodeInfo> editables = new java.util.ArrayList<>();
            collectEditTexts(root, editables);

            AccessibilityNodeInfo inputField = null;
            int bestY = -1;
            for (AccessibilityNodeInfo node : editables) {
                android.graphics.Rect bounds = new android.graphics.Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.centerY() > bestY) {
                    bestY = bounds.centerY();
                    inputField = node;
                }
            }

            if (inputField == null) {
                // No EditText found — fall back to LLM to find input + send
                XLog.i(TAG, "typeAndSend: no EditText found, falling back to LLM");
                return typeAndSendViaLlm(service, message);
            }

            // Step 2: Type the message
            inputField.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Thread.sleep(300);
            android.os.Bundle args = new android.os.Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message);
            inputField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            Thread.sleep(500);

            // Step 3: Find send button — try multiple generic patterns
            root = service.getRootInActiveWindow();
            if (root == null) root = service.getRootInActiveWindow();

            android.graphics.Rect inputBounds = getBottomEditTextBounds(root);
            AccessibilityNodeInfo sendBtn = UiActionMatchUtils.findBestSendAction(root, inputBounds);

            if (sendBtn != null) {
                service.clickNode(sendBtn);
                return ToolResult.success("Sent in open chat: " + message);
            }

            // Java send button search failed — ask LLM to find it
            XLog.i(TAG, "typeAndSend: send button not found by Java, asking LLM");
            return tapSendViaLlm(service, message);

        } catch (Exception e) {
            XLog.e(TAG, "typeAndSendInOpenChat failed", e);
            return ToolResult.error("Failed: " + e.getMessage());
        }
    }

    /**
     * LLM-assisted: ask the LLM to find the send button on the current screen.
     * Single targeted LLM call — NOT a full agent loop. Fast (~2s).
     */
    private ToolResult tapSendViaLlm(ClawAccessibilityService service, String alreadyTypedMessage) {
        try {
            // Read current screen as text
            io.agents.pokeclaw.tool.impl.GetScreenInfoTool screenTool = new io.agents.pokeclaw.tool.impl.GetScreenInfoTool();
            io.agents.pokeclaw.tool.ToolResult screenResult = screenTool.execute(java.util.Collections.emptyMap());
            if (!screenResult.isSuccess() || screenResult.getData() == null) {
                // Last resort: press Enter
                Runtime.getRuntime().exec(new String[]{"input", "keyevent", "66"}).waitFor();
                return ToolResult.success("Sent via Enter (screen read failed): " + alreadyTypedMessage);
            }

            String screenText = screenResult.getData();
            String prompt = "I already typed a message in the chat input field. " +
                "Now I need to tap the control that actually sends it. " +
                "It may be an icon-only button or a localized label, so do not require an exact text match.\n\n" +
                "Current screen:\n" + screenText + "\n\n" +
                "Which element is the real send control? Reply with ONLY the node ID (e.g., n5) or coordinates (e.g., 950,2100). Nothing else.";

            String response = singleLlmCall(prompt);
            if (response == null || response.isEmpty()) {
                Runtime.getRuntime().exec(new String[]{"input", "keyevent", "66"}).waitFor();
                return ToolResult.success("Sent via Enter (LLM empty): " + alreadyTypedMessage);
            }

            // Parse response — expect "n5" or "950,2100"
            response = response.trim().replaceAll("[\"'`]", "");
            if (response.startsWith("n")) {
                // Node ID — use tap_node tool
                io.agents.pokeclaw.tool.ToolRegistry registry = io.agents.pokeclaw.tool.ToolRegistry.getInstance();
                java.util.Map<String, Object> params = new java.util.HashMap<>();
                params.put("node_id", response);
                registry.executeTool("tap_node", params);
                return ToolResult.success("Sent via LLM node tap: " + alreadyTypedMessage);
            } else if (response.contains(",")) {
                // Coordinates
                String[] parts = response.split(",");
                int x = Integer.parseInt(parts[0].trim());
                int y = Integer.parseInt(parts[1].trim());
                service.performTap(x, y);
                return ToolResult.success("Sent via LLM coordinate tap: " + alreadyTypedMessage);
            }

            // Can't parse — fallback Enter
            Runtime.getRuntime().exec(new String[]{"input", "keyevent", "66"}).waitFor();
            return ToolResult.success("Sent via Enter (LLM parse fail): " + alreadyTypedMessage);

        } catch (Exception e) {
            XLog.w(TAG, "tapSendViaLlm failed, pressing Enter", e);
            try {
                Runtime.getRuntime().exec(new String[]{"input", "keyevent", "66"}).waitFor();
            } catch (Exception ignored) {}
            return ToolResult.success("Sent via Enter (LLM exception): " + alreadyTypedMessage);
        }
    }

    /**
     * Full LLM-assisted type and send — when no EditText is found at all.
     * Asks LLM to identify both input field and send button from the screen.
     */
    private ToolResult typeAndSendViaLlm(ClawAccessibilityService service, String message) {
        try {
            io.agents.pokeclaw.tool.impl.GetScreenInfoTool screenTool = new io.agents.pokeclaw.tool.impl.GetScreenInfoTool();
            io.agents.pokeclaw.tool.ToolResult screenResult = screenTool.execute(java.util.Collections.emptyMap());
            if (!screenResult.isSuccess() || screenResult.getData() == null) {
                return ToolResult.error("Cannot read screen for LLM-assisted send");
            }

            String screenText = screenResult.getData();
            String prompt = "I'm in a messaging chat and need to type and send a message.\n" +
                "Message to send: \"" + message + "\"\n\n" +
                "Current screen:\n" + screenText + "\n\n" +
                "Choose controls by function, not by exact wording. The send control might be icon-only or localized.\n" +
                "Tell me the steps as JSON array. Example:\n" +
                "[{\"action\":\"tap\",\"target\":\"n5\"},{\"action\":\"type\",\"text\":\"hello\"},{\"action\":\"tap\",\"target\":\"n8\"}]\n" +
                "Reply with ONLY the JSON array. Target can be node ID (n5) or coordinates (540,1800).";

            String response = singleLlmCall(prompt);
            if (response == null || response.isEmpty()) {
                return ToolResult.error("LLM returned empty response for type+send");
            }

            // Parse and execute the action sequence
            return executeLlmActions(service, response, message);

        } catch (Exception e) {
            XLog.e(TAG, "typeAndSendViaLlm failed", e);
            return ToolResult.error("LLM-assisted send failed: " + e.getMessage());
        }
    }

    /**
     * Execute a JSON array of actions returned by the LLM.
     */
    private ToolResult executeLlmActions(ClawAccessibilityService service, String jsonResponse, String message) {
        try {
            // Extract JSON array from response (LLM might add explanation text)
            String json = jsonResponse.trim();
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start < 0 || end <= start) {
                return ToolResult.error("Invalid LLM action response: " + json);
            }
            json = json.substring(start, end + 1);

            com.google.gson.JsonArray actions = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
            io.agents.pokeclaw.tool.ToolRegistry registry = io.agents.pokeclaw.tool.ToolRegistry.getInstance();

            for (int i = 0; i < actions.size(); i++) {
                com.google.gson.JsonObject action = actions.get(i).getAsJsonObject();
                String actionType = action.get("action").getAsString();
                Thread.sleep(300);

                switch (actionType) {
                    case "tap": {
                        String target = action.get("target").getAsString().trim();
                        if (target.startsWith("n")) {
                            java.util.Map<String, Object> params = new java.util.HashMap<>();
                            params.put("node_id", target);
                            registry.executeTool("tap_node", params);
                        } else if (target.contains(",")) {
                            String[] parts = target.split(",");
                            service.performTap(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
                        }
                        break;
                    }
                    case "type": {
                        // Use the actual message, not what LLM echoed (could be truncated)
                        String text = action.has("text") ? action.get("text").getAsString() : message;
                        AccessibilityNodeInfo root = service.getRootInActiveWindow();
                        if (root != null) {
                            java.util.List<AccessibilityNodeInfo> editables = new java.util.ArrayList<>();
                            collectEditTexts(root, editables);
                            if (!editables.isEmpty()) {
                                AccessibilityNodeInfo field = editables.get(editables.size() - 1);
                                android.os.Bundle args = new android.os.Bundle();
                                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                            }
                        }
                        break;
                    }
                }
            }

            return ToolResult.success("Sent via LLM actions: " + message);

        } catch (Exception e) {
            XLog.e(TAG, "executeLlmActions failed", e);
            return ToolResult.error("LLM action execution failed: " + e.getMessage());
        }
    }

    /**
     * Single LLM call — NOT an agent loop. Uses the user's selected Cloud/Local model.
     * For quick targeted questions like "which node is the send button?"
     */
    private String singleLlmCall(String prompt) {
        return io.agents.pokeclaw.agent.llm.LlmSessionManager.INSTANCE.singleShot(prompt, 0.3);
    }

    private android.graphics.Rect getBottomEditTextBounds(AccessibilityNodeInfo root) {
        java.util.List<AccessibilityNodeInfo> editables = new java.util.ArrayList<>();
        collectEditTexts(root, editables);
        AccessibilityNodeInfo bottom = null;
        int bestY = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo node : editables) {
            android.graphics.Rect bounds = new android.graphics.Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.centerY() > bestY) {
                bestY = bounds.centerY();
                bottom = node;
            }
        }
        if (bottom == null) return null;
        android.graphics.Rect bounds = new android.graphics.Rect();
        bottom.getBoundsInScreen(bounds);
        return bounds;
    }

    /** Collect text nodes in top 300px (toolbar area) */
    private void collectTopBarNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.top < 300 && (node.getText() != null || node.getContentDescription() != null)) {
            result.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectTopBarNodes(child, result);
        }
    }

    /** Recursively find nodes whose text or contentDescription contains target */
    private void findNodesContainingText(
        AccessibilityNodeInfo node,
        Set<String> normalizedAliases,
        Set<String> digitAliases,
        List<AccessibilityNodeInfo> results
    ) {
        if (node == null) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (ContactMatchUtils.matchesTarget(text, desc, normalizedAliases, digitAliases)) {
            results.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) findNodesContainingText(child, normalizedAliases, digitAliases, results);
        }
    }

    private void collectEditTexts(AccessibilityNodeInfo node, java.util.List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        CharSequence cn = node.getClassName();
        if (node.isEditable() || (cn != null && cn.toString().contains("EditText"))) {
            result.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectEditTexts(child, result);
        }
    }

    /**
     * Read the last N messages visible on screen for conversation context.
     * Returns formatted string like:
     *   them: "Are you coming home?"
     *   me: "Yep, I'll be there soon!"
     *   them: "Bring two bottles of water"
     */
    private String readConversationContext() {
        ClawAccessibilityService service = ClawAccessibilityService.getConnectedInstance(12000L);
        if (service == null) return "";

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return "";

        List<AccessibilityNodeInfo> allText = new ArrayList<>();
        collectAllTextNodes(root, allText);

        android.graphics.Rect rootBounds = new android.graphics.Rect();
        root.getBoundsInScreen(rootBounds);
        int midX = rootBounds.centerX();

        StringBuilder context = new StringBuilder();
        int msgCount = 0;

        for (AccessibilityNodeInfo node : allText) {
            CharSequence text = node.getText();
            if (text == null || text.length() == 0 || text.length() > 200) continue;

            android.graphics.Rect bounds = new android.graphics.Rect();
            node.getBoundsInScreen(bounds);

            // Skip toolbar and input area (bottom 250px)
            if (bounds.top < 300 || bounds.top > rootBounds.height() - 250) continue;

            String msg = text.toString();
            if (ChatNoiseFilterUtils.isLikelyNonMessageLabel(bounds, rootBounds, msg)) continue;

            // Left = them, Right = me
            String speaker = bounds.centerX() < midX ? "them" : "me";
            context.append(speaker).append(": \"").append(msg).append("\"\n");
            msgCount++;
        }

        XLog.i(TAG, "readConversationContext: " + msgCount + " messages");
        // Keep last 10 messages max
        String[] lines = context.toString().split("\n");
        if (lines.length > 10) {
            StringBuilder trimmed = new StringBuilder();
            for (int i = lines.length - 10; i < lines.length; i++) {
                trimmed.append(lines[i]).append("\n");
            }
            return trimmed.toString();
        }
        return context.toString();
    }

    private void collectAllTextNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        if (node.getText() != null && node.getText().length() > 0) result.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectAllTextNodes(child, result);
        }
    }

    private String resolveAppName(String packageName) {
        switch (packageName) {
            case "com.whatsapp": return "WhatsApp";
            case "org.telegram.messenger": return "Telegram";
            case "com.google.android.apps.messaging": return "Messages";
            case "jp.naver.line.android": return "LINE";
            case "com.tencent.mm": return "WeChat";
            default: return "WhatsApp";
        }
    }

    private String normalizeAppName(String appName) {
        if (appName == null) return "WhatsApp";
        String lower = appName.trim().toLowerCase(java.util.Locale.ROOT);
        switch (lower) {
            case "telegram":
                return "Telegram";
            case "messages":
            case "google messages":
            case "sms":
                return "Messages";
            case "line":
                return "LINE";
            case "wechat":
            case "we chat":
                return "WeChat";
            default:
                return "WhatsApp";
        }
    }

    private String resolvePackageName(String appName) {
        switch (appName) {
            case "WhatsApp":
                return "com.whatsapp";
            case "Telegram":
                return "org.telegram.messenger";
            case "Messages":
                return "com.google.android.apps.messaging";
            case "LINE":
                return "jp.naver.line.android";
            case "WeChat":
                return "com.tencent.mm";
            default:
                return null;
        }
    }
}
