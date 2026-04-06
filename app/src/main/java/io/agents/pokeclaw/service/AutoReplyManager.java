// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.service;

import android.app.Notification;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import io.agents.pokeclaw.tool.ToolRegistry;
import io.agents.pokeclaw.tool.ToolResult;
import io.agents.pokeclaw.tool.impl.SendMessageTool;
import io.agents.pokeclaw.utils.XLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 *   AutoReplyManager.getInstance().addContact("Mom");
 *
 * When enabled, intercepts notifications from messaging apps,
 * extracts sender + message, generates a reply via on-device LLM,
 * and sends it via SendMessageTool.
 */
public class AutoReplyManager {

    private static final String TAG = "AutoReplyManager";
    private static AutoReplyManager instance;

    private boolean enabled = false;
    private final Set<String> monitoredContacts = new HashSet<>();
    private final Set<String> monitoredApps = new HashSet<>();
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
    }

    public static synchronized AutoReplyManager getInstance() {
        if (instance == null) instance = new AutoReplyManager();
        return instance;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        XLog.i(TAG, "Auto-reply " + (enabled ? "ENABLED" : "DISABLED") +
                " for contacts: " + monitoredContacts);
    }

    public boolean isEnabled() { return enabled; }

    public void addContact(String name) {
        monitoredContacts.add(name.toLowerCase());
        XLog.i(TAG, "Added contact: " + name);
    }

    public void removeContact(String name) {
        monitoredContacts.remove(name.toLowerCase());
    }

    public void clearContacts() {
        monitoredContacts.clear();
    }

    /**
     * Called from ClawNotificationListener (primary, reliable) or
     * ClawAccessibilityService (fallback if NotificationListener not enabled).
     */
    public void onNotificationReceived(String packageName, String title, String text) {
        if (!enabled) return;
        if (!monitoredApps.contains(packageName)) return;
        if (title.isEmpty() || text.isEmpty()) return;

        XLog.d(TAG, "Notification from " + packageName + ": title='" + title + "' text='" + text + "'");
        handleIncomingMessage(packageName, title, text);
    }

    /**
     * Fallback: Called from ClawAccessibilityService.onAccessibilityEvent.
     * Only used if NotificationListenerService is not connected.
     */
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!enabled) return;

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

    private void handleIncomingMessage(String packageName, String title, String text) {

        // Check if sender is in our monitored list
        String senderLower = title.toLowerCase();
        boolean isMonitored = false;
        String matchedContact = "";
        for (String contact : monitoredContacts) {
            if (senderLower.contains(contact)) {
                isMonitored = true;
                matchedContact = title; // Use original case
                break;
            }
        }
        if (!isMonitored) return;

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
        Long lastReply = lastReplyTime.get(senderLower);
        if (lastReply != null && (now - lastReply) < DEBOUNCE_MS) {
            XLog.d(TAG, "Debounce: skipping reply to " + matchedContact + " (replied " + ((now - lastReply) / 1000) + "s ago)");
            return;
        }

        // If already replying, just flag it — after current reply we'll re-open chat,
        // read the full screen (all messages including new ones), and reply to latest.
        if (!replying.compareAndSet(false, true)) {
            hasPending = true;
            pendingPackage = packageName;
            pendingContact = matchedContact;
            XLog.d(TAG, "Already replying, flagged pending from " + matchedContact);
            return;
        }

        String finalContact = matchedContact;
        String incomingMessage = text;
        String appName = resolveAppName(packageName);

        XLog.i(TAG, "Auto-replying to " + finalContact + " via " + appName + ": '" + incomingMessage + "'");

        executor.submit(() -> {
            try {
                // Open the messaging app and navigate to the contact's chat
                ClawAccessibilityService svc = ClawAccessibilityService.getInstance();
                if (svc == null) {
                    XLog.e(TAG, "No accessibility service, cannot open chat");
                    return;
                }

                svc.openApp(packageName);
                Thread.sleep(2000);

                // Find and tap the contact to open their chatroom
                AccessibilityNodeInfo root = svc.getRootInActiveWindow();
                if (root != null) {
                    // Check if already in the right chat (contact name in toolbar)
                    boolean inChat = false;
                    List<AccessibilityNodeInfo> topNodes = new ArrayList<>();
                    collectTopBarNodes(root, topNodes);
                    for (AccessibilityNodeInfo node : topNodes) {
                        CharSequence t = node.getText();
                        if (t != null && t.toString().toLowerCase().contains(finalContact.toLowerCase())) {
                            inChat = true;
                            break;
                        }
                    }

                    if (!inChat) {
                        // Not in chat — find contact in chat list and tap
                        List<AccessibilityNodeInfo> matches = new ArrayList<>();
                        findNodesContainingText(root, finalContact.toLowerCase(), matches);
                        if (!matches.isEmpty()) {
                            svc.clickNode(matches.get(0));
                            XLog.i(TAG, "Tapped contact: " + finalContact);
                            Thread.sleep(2000);
                        } else {
                            XLog.w(TAG, "Could not find " + finalContact + " in chat list");
                        }
                    } else {
                        XLog.i(TAG, "Already in " + finalContact + "'s chat");
                    }
                }

                // NOW read conversation context — chat is visible on screen
                String context = readConversationContext();
                XLog.i(TAG, "Context before reply: " + (context.isEmpty() ? "(empty)" : context.length() + " chars"));

                // Generate reply with full context
                String reply = generateReply(finalContact, incomingMessage, context);

                // Track our own message to avoid loop
                ownSentMessages.add(reply);

                // We're already in the chatroom from the navigate step above
                ToolResult result;

                if (svc != null && isInChatroom(svc, finalContact)) {
                    // Fast path: already in chatroom, just type + send
                    XLog.i(TAG, "Fast path: already in chatroom, typing directly");
                    result = typeAndSendInOpenChat(svc, reply);
                } else {
                    // Full path: open app, find contact, type, send
                    SendMessageTool sendTool = new SendMessageTool();
                    Map<String, Object> params = new HashMap<>();
                    params.put("contact", finalContact);
                    params.put("message", reply);
                    params.put("app", appName);
                    result = sendTool.execute(params);
                }

                if (result.isSuccess()) {
                    XLog.i(TAG, "Auto-reply sent to " + finalContact + ": '" + reply + "'");
                    lastReplyTime.put(senderLower, System.currentTimeMillis());

                    // Dismiss the messaging app's notifications so the next message
                    // triggers a fresh notification event (not an update to existing one).
                    ClawNotificationListener.dismissNotifications(packageName);

                    // Press Home after reply so next message arrives as notification.
                    try {
                        Thread.sleep(1000);
                        ClawAccessibilityService homeSvc = ClawAccessibilityService.getInstance();
                        if (homeSvc != null) {
                            homeSvc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
                            XLog.i(TAG, "Pressed Home after reply");
                        }
                    } catch (Exception he) {
                        XLog.w(TAG, "Failed to press Home", he);
                    }
                } else {
                    XLog.e(TAG, "Auto-reply failed: " + result.getError());
                }
            } catch (Exception e) {
                XLog.e(TAG, "Auto-reply error", e);
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

    /**
     * Generate a reply using on-device LLM.
     * Falls back to simple default if LLM fails.
     */
    private String generateReply(String sender, String incomingMessage, String conversationContext) {
        try {
            String modelPath = io.agents.pokeclaw.utils.KVUtils.INSTANCE.getLocalModelPath();
            if (modelPath == null || modelPath.isEmpty()) {
                XLog.w(TAG, "generateReply: no model path, using fallback");
                return fallbackReply(incomingMessage);
            }

            // LiteRT-LM only supports one session at a time. Close the engine
            // entirely to force-release any existing session (chat UI or task agent),
            // then re-create. This is a ~2s hit but guarantees we get a session.
            String cacheDir = io.agents.pokeclaw.ClawApplication.Companion.getInstance().getCacheDir().getPath();
            io.agents.pokeclaw.agent.llm.EngineHolder.INSTANCE.close();
            com.google.ai.edge.litertlm.Engine engine =
                io.agents.pokeclaw.agent.llm.EngineHolder.INSTANCE.getOrCreate(modelPath, cacheDir);

            com.google.ai.edge.litertlm.Contents sysPrompt = com.google.ai.edge.litertlm.Contents.Companion.of(
                "You are replying to a chat message on behalf of the user. " +
                "Keep replies SHORT (under 15 words), casual, and friendly. " +
                "Reply in the same language as the incoming message. " +
                "Do NOT use emojis excessively. Sound like a real person texting."
            );
            com.google.ai.edge.litertlm.SamplerConfig sampler =
                new com.google.ai.edge.litertlm.SamplerConfig(64, 0.95, 0.7, 0);
            com.google.ai.edge.litertlm.Conversation conv = engine.createConversation(
                new com.google.ai.edge.litertlm.ConversationConfig(sysPrompt, java.util.Collections.emptyList(), java.util.Collections.emptyList(), sampler)
            );

            String prompt;
            if (conversationContext != null && !conversationContext.isEmpty()) {
                // Context has the full conversation visible on screen.
                // Read everything, address ALL points from them that haven't been replied to yet.
                prompt = "Recent conversation:\n" + conversationContext + "\n" +
                         "Read the entire conversation above. Reply to ALL of " + sender + "'s messages that you (me) haven't responded to yet. Address every point in one reply. Your reply:";
            } else {
                prompt = sender + " says: \"" + incomingMessage + "\"\nYour reply:";
            }
            com.google.ai.edge.litertlm.Message response = conv.sendMessage(prompt, java.util.Collections.emptyMap());
            conv.close();

            String reply = response.getContents() != null ? response.getContents().toString().trim() : "";
            // Clean up — remove quotes, "Your reply:" prefix, etc.
            reply = reply.replaceAll("^[\"']|[\"']$", "").trim();
            if (reply.startsWith("Your reply:")) reply = reply.substring(11).trim();

            if (reply.isEmpty() || reply.length() > 200) {
                XLog.w(TAG, "generateReply: LLM reply too long or empty, using fallback");
                return fallbackReply(incomingMessage);
            }

            XLog.i(TAG, "generateReply: LLM generated '" + reply + "' for '" + incomingMessage + "'");
            return reply;

        } catch (Exception e) {
            XLog.w(TAG, "generateReply: LLM failed, using fallback", e);
            return fallbackReply(incomingMessage);
        }
    }

    private String fallbackReply(String message) {
        String lower = message.toLowerCase();
        if (lower.contains("hi") || lower.contains("hello") || lower.contains("hey")) return "Hey! What's up?";
        if (lower.contains("miss") || lower.contains("love")) return "Miss you too! ❤️";
        return "Got it, thanks!";
    }

    /**
     * Find contact name from the messaging app toolbar.
     * Works generically — looks for a TextView near the top of screen.
     */
    private String findContactNameInToolbar(AccessibilityNodeInfo root) {
        // Look for clickable container near top with a text child (contact name)
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        collectTextNodesInRegion(root, 0, 300, candidates); // top 300px = toolbar area

        for (AccessibilityNodeInfo node : candidates) {
            CharSequence text = node.getText();
            if (text != null && text.length() > 0 && text.length() < 30) {
                String t = text.toString();
                // Skip timestamps, status texts
                if (t.contains(":") && t.length() < 10) continue; // "7:47 p.m."
                if (t.toLowerCase().contains("last seen") || t.toLowerCase().contains("online")) continue;
                if (t.toLowerCase().contains("typing")) continue;
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
                // Skip system messages, timestamps, and input hints
                if (msg.contains("end-to-end encrypted") || msg.contains("Learn more")) continue;
                if (msg.equals("Today") || msg.equals("Yesterday")) continue;
                if (msg.equals("Message") || msg.equals("Type a message")) continue;
                // Skip timestamps: short text with time patterns
                if (msg.length() < 15 && (msg.contains(":") && (msg.contains("a.m.") || msg.contains("p.m.") || msg.matches(".*\\d+:\\d+.*")))) continue;
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
        return toolbarName.toLowerCase().contains(contact.toLowerCase());
    }

    /**
     * Type and send a message when already inside the chatroom.
     * Much faster than SendMessageTool (no need to open app + find contact).
     */
    private ToolResult typeAndSendInOpenChat(ClawAccessibilityService service, String message) {
        try {
            // Find bottom EditText and type
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) return ToolResult.error("No active window");

            java.util.List<AccessibilityNodeInfo> editables = new java.util.ArrayList<>();
            collectEditTexts(root, editables);

            AccessibilityNodeInfo best = null;
            int bestY = -1;
            for (AccessibilityNodeInfo node : editables) {
                android.graphics.Rect bounds = new android.graphics.Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.centerY() > bestY) {
                    bestY = bounds.centerY();
                    best = node;
                }
            }
            if (best == null) return ToolResult.error("No input field found");

            best.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Thread.sleep(300);
            android.os.Bundle args = new android.os.Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message);
            best.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            Thread.sleep(500);

            // Find and tap send button by content description
            AccessibilityNodeInfo sendBtn = findNodeByDesc(root, "send");
            if (sendBtn != null) {
                service.clickNode(sendBtn);
                return ToolResult.success("Sent in open chat: " + message);
            }

            // Fallback: press Enter
            Runtime.getRuntime().exec(new String[]{"input", "keyevent", "66"}).waitFor();
            return ToolResult.success("Sent via Enter: " + message);

        } catch (Exception e) {
            XLog.e(TAG, "typeAndSendInOpenChat failed", e);
            return ToolResult.error("Failed: " + e.getMessage());
        }
    }

    /** Collect text nodes in top 300px (toolbar area) */
    private void collectTopBarNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.top < 300 && node.getText() != null) {
            result.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectTopBarNodes(child, result);
        }
    }

    /** Recursively find nodes whose text or contentDescription contains target */
    private void findNodesContainingText(AccessibilityNodeInfo node, String lowerTarget, List<AccessibilityNodeInfo> results) {
        if (node == null) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if ((text != null && text.toString().toLowerCase().contains(lowerTarget)) ||
            (desc != null && desc.toString().toLowerCase().contains(lowerTarget))) {
            results.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) findNodesContainingText(child, lowerTarget, results);
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
        ClawAccessibilityService service = ClawAccessibilityService.getInstance();
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
            // Skip system messages, timestamps, input hints
            if (msg.contains("end-to-end encrypted") || msg.contains("Learn more")) continue;
            if (msg.equals("Today") || msg.equals("Yesterday")) continue;
            if (msg.equals("Message") || msg.equals("Type a message")) continue;
            if (msg.length() < 15 && (msg.contains(":") && (msg.contains("a.m.") || msg.contains("p.m.") || msg.matches(".*\\d+:\\d+.*")))) continue;

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

    private AccessibilityNodeInfo findNodeByDesc(AccessibilityNodeInfo node, String keyword) {
        if (node == null) return null;
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.toString().toLowerCase().contains(keyword.toLowerCase())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = findNodeByDesc(child, keyword);
            if (found != null) return found;
        }
        return null;
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
            default: return "WhatsApp";
        }
    }
}
