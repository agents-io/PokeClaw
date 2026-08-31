// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import io.agents.pokeclaw.service.ClawAccessibilityService;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;
import io.agents.pokeclaw.utils.ContactListUiUtils;
import io.agents.pokeclaw.utils.UiActionMatchUtils;
import io.agents.pokeclaw.utils.ContactMatchUtils;
import io.agents.pokeclaw.utils.XLog;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Generic high-level tool: sends a message to a contact in ANY messaging app.
 *
 * Strategy (app-agnostic):
 * 1. Open the app via package name (resolved from common names)
 * 2. Wait for app window to become active
 * 3. Find contact by traversing the accessibility tree (not findNodesByText which is unreliable)
 * 4. Tap contact to open chat
 * 5. Find the bottommost EditText (message input, not search bar)
 * 6. Set text via accessibility ACTION_SET_TEXT
 * 7. Find send button by content description containing "send" in any language
 * 8. If no send button found, press Enter key as fallback
 */
public class SendMessageTool extends BaseTool {

    private static final String TAG = "SendMessageTool";

    @Override
    @NotNull
    public String getName() { return "send_message"; }

    @Override
    @NotNull
    public String getDisplayName() { return "Send Message"; }

    @Override
    @NotNull
    public String getDescriptionEN() {
        return "Send a text message to a contact via any messaging app (WhatsApp, Telegram, Messages, etc).";
    }

    @Override
    @NotNull
    public String getDescriptionCN() {
        return "Send a text message to a contact via any messaging app (WhatsApp, Telegram, Messages, etc).";
    }

    @Override
    @NotNull
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("contact", "string", "Contact name or phone number to message (e.g. 'Mom', '+1 604 555 1234')", true),
                new ToolParameter("message", "string", "The message text to send", true),
                new ToolParameter("app", "string", "Messaging app name (default: WhatsApp)", false)
        );
    }

    @Override
    public ToolResult execute(@NotNull Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }

        String contact = requireString(params, "contact");
        String message = requireString(params, "message");
        Object appParam = params.get("app");
        String app = appParam != null ? appParam.toString() : "WhatsApp";

        XLog.i(TAG, "Sending '" + message + "' to " + contact + " via " + app);

        try {
            // Step 1: Resolve and open the messaging app
            String packageName = OpenAppTool.resolveAppNameStatic(app);
            if (packageName == null) packageName = app;
            boolean opened = OpenAppTool.openAppWithInterceptHandling(service, packageName);
            if (!opened) {
                return ToolResult.error("Failed to open " + app + ". Is it installed?");
            }
            XLog.i(TAG, "Step 1: Opened " + app + " (" + packageName + ") with intercept handling");
            Thread.sleep(2000);

            // Step 2: Wait for the messaging app window to become active
            if (!waitForActiveWindow(service, packageName, 8000)) {
                return ToolResult.error(app + " did not become active. Is accessibility enabled?");
            }
            XLog.i(TAG, "Step 2: " + app + " is active window");

            // Step 3: Check if we're ALREADY in the correct chatroom
            // (e.g. opened via notification tap — no need to navigate)
            if (isAlreadyInChatWith(service, contact)) {
                XLog.i(TAG, "Step 3: Already in " + contact + "'s chatroom — skipping navigation");
            } else {
                // Navigate to chat list and find contact
                XLog.i(TAG, "Step 3: Not in chatroom, navigating to " + contact);
                if (!ContactListUiUtils.prepareForContactLookup(service, packageName, 4, 1200)) {
                    AccessibilityNodeInfo activeRoot = service.getRootInActiveWindow();
                    CharSequence activePkg = activeRoot != null ? activeRoot.getPackageName() : null;
                    XLog.w(TAG, "Step 3: contact lookup not ready; activePkg=" + activePkg + " targetPkg=" + packageName);
                    return ToolResult.error("Could not reach a searchable " + app + " chat list.");
                }

                if (!findAndTapContact(service, contact)) {
                    return ToolResult.error("Could not find '" + contact + "' in " + app + " chat list.");
                }
                XLog.i(TAG, "Step 3: Tapped " + contact);
                Thread.sleep(3000);
                waitForActiveWindow(service, packageName, 5000);
            }

            // Step 4: Type message in the bottommost input field (retry — chat may still be loading)
            boolean typed = false;
            for (int retry = 0; retry < 5; retry++) {
                if (typeInBottomEditText(service, message)) {
                    typed = true;
                    break;
                }
                XLog.i(TAG, "Step 4: retry " + (retry + 1) + " — waiting for chat to load");
                Thread.sleep(1000);
            }
            if (!typed) {
                return ToolResult.error("Could not find message input field.");
            }
            XLog.i(TAG, "Step 4: Typed '" + message + "'");
            Thread.sleep(500);

            // Step 5: Tap send (by desc) or press Enter as fallback
            if (!tapSendOrEnter(service, message)) {
                return ToolResult.error("Could not find send button.");
            }
            XLog.i(TAG, "Step 5: Sent!");
            return ToolResult.success("Sent '" + message + "' to " + contact + " via " + app);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted");
        } catch (Exception e) {
            XLog.e(TAG, "Failed", e);
            return ToolResult.error("Failed: " + e.getMessage());
        }
    }

    /**
     * Check if the current screen is already the chatroom for the given contact.
     * Looks for the contact name in the top toolbar area.
     */
    private boolean isAlreadyInChatWith(ClawAccessibilityService service, String contact) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;
        java.util.LinkedHashSet<String> normalizedAliases = ContactMatchUtils.buildNormalizedAliases(contact);
        java.util.LinkedHashSet<String> digitAliases = ContactMatchUtils.buildDigitAliases(contact);

        // Search top 300px of screen for a text matching the contact name
        List<AccessibilityNodeInfo> topNodes = new ArrayList<>();
        collectTextNodesInRegion(root, 0, 300, topNodes);
        for (AccessibilityNodeInfo node : topNodes) {
            if (ContactMatchUtils.matchesTarget(node.getText(), node.getContentDescription(), normalizedAliases, digitAliases)) {
                XLog.d(TAG, "isAlreadyInChatWith: matched toolbar target text=" + node.getText() + " desc=" + node.getContentDescription());
                return true;
            }
        }
        return false;
    }

    private void collectTextNodesInRegion(AccessibilityNodeInfo node, int minY, int maxY, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.top >= minY && bounds.bottom <= maxY && (node.getText() != null || node.getContentDescription() != null)) {
            result.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectTextNodesInRegion(child, minY, maxY, result);
        }
    }

    // ── Generic helpers (no app-specific logic) ──

    /**
     * Wait until the given package is the active window.
     * Works for ANY app — just checks packageName on root node.
     */
    private boolean waitForActiveWindow(ClawAccessibilityService service, String packageName, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null) {
                CharSequence pkg = root.getPackageName();
                XLog.d(TAG, "waitForActiveWindow: current=" + pkg + " want=" + packageName);
                if (pkg != null && pkg.toString().equals(packageName)) return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    /**
     * Find a contact by manually traversing the full accessibility tree.
     * More reliable than findAccessibilityNodeInfosByText which misses nodes in some apps.
     */
    private boolean findAndTapContact(ClawAccessibilityService service, String contact) throws InterruptedException {
        java.util.LinkedHashSet<String> normalizedAliases = ContactMatchUtils.buildNormalizedAliases(contact);
        java.util.LinkedHashSet<String> digitAliases = ContactMatchUtils.buildDigitAliases(contact);
        return ContactListUiUtils.searchOrScrollAndFindAndClick(service, contact, normalizedAliases, digitAliases, 12, 800);
    }

    /**
     * Recursively collect nodes whose text or contentDescription contains the target string.
     * This is our own tree walk — does not rely on the flaky findAccessibilityNodeInfosByText API.
     */
    private void collectNodesWithText(
        AccessibilityNodeInfo node,
        java.util.LinkedHashSet<String> normalizedAliases,
        java.util.LinkedHashSet<String> digitAliases,
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
            if (child != null) {
                collectNodesWithText(child, normalizedAliases, digitAliases, results);
            }
        }
    }

    /**
     * Find the bottommost EditText on screen and type into it.
     * In messaging apps, the message input is always at the bottom.
     * Search bars are at the top — we pick the one with the highest Y value.
     */
    private boolean typeInBottomEditText(ClawAccessibilityService service, String message) throws InterruptedException {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            XLog.w(TAG, "typeInBottomEditText: root is null!");
            return false;
        }
        XLog.d(TAG, "typeInBottomEditText: root package=" + root.getPackageName());

        List<AccessibilityNodeInfo> editables = new ArrayList<>();
        collectEditTexts(root, editables);
        XLog.i(TAG, "typeInBottomEditText: found " + editables.size() + " EditText nodes in " + root.getPackageName());

        if (editables.isEmpty()) return false;

        // Pick the one with the largest Y coordinate (bottommost = message input)
        AccessibilityNodeInfo best = null;
        int bestY = -1;
        for (AccessibilityNodeInfo node : editables) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            XLog.d(TAG, "  EditText at y=" + bounds.centerY() + " text=" + node.getText() + " hint=" + node.getHintText());
            if (bounds.centerY() > bestY) {
                bestY = bounds.centerY();
                best = node;
            }
        }

        if (best == null) return false;

        // Focus + click + set text
        best.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        best.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Thread.sleep(500);

        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message);
        boolean ok = best.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        XLog.i(TAG, "typeInBottomEditText: setText='" + message + "' at y=" + bestY + " result=" + ok);
        return ok;
    }

    /**
     * Collect all EditText nodes by checking both isEditable() and className.
     * Some apps (WhatsApp) have EditText that doesn't report isEditable().
     */
    private void collectEditTexts(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        boolean isEditable = node.isEditable();
        CharSequence cn = node.getClassName();
        boolean isEditText = cn != null && cn.toString().contains("EditText");
        if (isEditable || isEditText) {
            result.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectEditTexts(child, result);
        }
    }

    /**
     * Find and tap the send button. Strategy (generic, no app-specific IDs):
     * 1. Search tree for any node with contentDescription containing "send" (any language)
     * 2. If multiple matches, pick the one near the bottom-right (typical send button position)
     * 3. Fallback: press Enter key without leaving the current chat
     */
    private boolean tapSendOrEnter(ClawAccessibilityService service, String expectedMessage) throws InterruptedException {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;

        Rect inputBounds = findBottomEditTextBounds(root);
        AccessibilityNodeInfo sendNode = UiActionMatchUtils.findBestSendAction(root, inputBounds);
        if (sendNode != null) {
            boolean clicked = service.clickNode(sendNode);
            XLog.i(TAG, "tapSendOrEnter: tapped structural send candidate, clicked=" + clicked);
            if (didMessageLeaveComposer(service, expectedMessage, "candidate")) return true;
        }

        // Fallback: press Enter without dismissing the keyboard first.
        // Going "Back" here can blur the input or even leave the chat screen in some apps.
        XLog.i(TAG, "tapSendOrEnter: no send button found, pressing Enter directly");
        try {
            service.sendKeyEvent(android.view.KeyEvent.KEYCODE_ENTER);
            return didMessageLeaveComposer(service, expectedMessage, "enter");
        } catch (Exception e) {
            XLog.w(TAG, "Enter key fallback failed", e);
        }
        return false;
    }

    private boolean didMessageLeaveComposer(
            ClawAccessibilityService service,
            String expectedMessage,
            String pathLabel
    ) throws InterruptedException {
        Thread.sleep(500);
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            XLog.i(TAG, "tapSendOrEnter: " + pathLabel + " verification root missing; treating as success");
            return true;
        }

        AccessibilityNodeInfo composer = findBottomEditText(root);
        if (composer == null) {
            XLog.i(TAG, "tapSendOrEnter: " + pathLabel + " verification composer missing; treating as success");
            return true;
        }

        CharSequence composerText = composer.getText();
        String current = composerText != null ? composerText.toString().trim() : "";
        String expected = expectedMessage != null ? expectedMessage.trim() : "";
        XLog.i(TAG, "tapSendOrEnter: " + pathLabel + " verification composerText='" + current + "'");

        if (current.isEmpty()) {
            return true;
        }

        if (expected.isEmpty()) {
            return !current.isEmpty();
        }

        return !current.equals(expected) && !current.contains(expected);
    }

    private Rect findBottomEditTextBounds(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo bottom = findBottomEditText(root);
        if (bottom == null) return null;
        Rect bounds = new Rect();
        bottom.getBoundsInScreen(bounds);
        return bounds;
    }

    private AccessibilityNodeInfo findBottomEditText(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> editTexts = new ArrayList<>();
        collectEditTexts(root, editTexts);
        AccessibilityNodeInfo bottom = null;
        int bestY = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo node : editTexts) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.centerY() > bestY) {
                bestY = bounds.centerY();
                bottom = node;
            }
        }
        return bottom;
    }

}
