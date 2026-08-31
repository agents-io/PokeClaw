// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.utils;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.agents.pokeclaw.service.ClawAccessibilityService;

/**
 * Generic contact/chat-list search helpers.
 *
 * The goal is to avoid app-specific assumptions:
 * - search current screen first
 * - prefer text matches over contentDescription-only matches
 * - scroll multiple times if needed
 * - stop once the screen stops changing
 */
public final class ContactListUiUtils {
    private static final String TAG = "ContactListUiUtils";
    private static final String[] CLOSE_HINTS = {
        "close", "dismiss", "cancel", "got it", "ok", "done",
        "關閉", "关闭", "取消", "知道了", "確定", "确定",
        "cerrar", "fermer", "schließen", "닫기", "閉じる", "закрыть", "fechar"
    };

    private enum SearchAttemptResult {
        FOUND,
        NO_MATCH,
        SEARCH_UI_MISSING,
        TYPE_FAILED
    }

    private ContactListUiUtils() {}

    public static boolean prepareForContactLookup(
        ClawAccessibilityService service,
        String packageName,
        int maxBacks,
        long settleMs
    ) throws InterruptedException {
        int attempts = Math.min(Math.max(maxBacks, 1), 6);
        int reopenAttempts = 0;
        final int maxReopens = 2;
        for (int attempt = 0; attempt <= attempts; attempt++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (dismissBlockingOverlay(service, root, settleMs)) {
                continue;
            }
            if (isContactLookupReady(root)) {
                XLog.i(TAG, "prepareForContactLookup: ready on attempt=" + attempt);
                return true;
            }

            if (attempt == attempts) {
                break;
            }

            CharSequence activePackage = root != null ? root.getPackageName() : null;
            if (activePackage == null || !activePackage.toString().equals(packageName)) {
                if (reopenAttempts >= maxReopens) {
                    XLog.w(TAG, "prepareForContactLookup: app not active after reopen budget; waiting (attempt=" + attempt + ")");
                } else {
                    reopenAttempts++;
                    XLog.i(TAG, "prepareForContactLookup: app not active, reopening " + packageName + " (reopen=" + reopenAttempts + "/" + maxReopens + ")");
                    service.openApp(packageName);
                }
            } else {
                if (dismissBlockingOverlay(service, root, settleMs)) {
                    XLog.i(TAG, "prepareForContactLookup: dismissed overlay, re-checking readiness");
                    continue;
                }

                if (tryExposeSearchUi(service, root, settleMs)) {
                    XLog.i(TAG, "prepareForContactLookup: exposed search UI, re-checking readiness");
                    continue;
                }

                XLog.i(TAG, "prepareForContactLookup: screen not ready, pressing back");
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            }

            Thread.sleep(Math.max(settleMs, 700L));
        }

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        boolean ready = isContactLookupReady(root);
        XLog.i(TAG, "prepareForContactLookup: final ready=" + ready);
        return ready;
    }

    public static boolean scrollAndFindAndClick(
        ClawAccessibilityService service,
        LinkedHashSet<String> normalizedAliases,
        LinkedHashSet<String> digitAliases,
        int maxScrolls,
        long settleMs
    ) throws InterruptedException {
        int attempts = Math.min(Math.max(maxScrolls, 1), 20);
        String lastScreen = safeScreenSnapshot(service);

        for (int attempt = 0; attempt <= attempts; attempt++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            AccessibilityNodeInfo bestMatch = findBestVisibleContactNode(root, normalizedAliases, digitAliases);
            if (bestMatch != null) {
                XLog.i(TAG, "scrollAndFindAndClick: matched node text=" + bestMatch.getText() + " desc=" + bestMatch.getContentDescription() + " on attempt=" + attempt);
                return service.clickNode(bestMatch);
            }

            if (attempt == attempts || root == null) {
                return false;
            }

            Rect rootBounds = new Rect();
            root.getBoundsInScreen(rootBounds);
            int centerX = rootBounds.centerX();
            int fromY = rootBounds.top + (int) (rootBounds.height() * 0.72f);
            int toY = rootBounds.top + (int) (rootBounds.height() * 0.28f);
            boolean swiped = service.performSwipe(centerX, fromY, centerX, toY, 320);
            XLog.i(TAG, "scrollAndFindAndClick: swipe attempt=" + (attempt + 1) + " result=" + swiped);
            if (!swiped) {
                return false;
            }

            Thread.sleep(settleMs);

            String currentScreen = safeScreenSnapshot(service);
            if (currentScreen != null && currentScreen.equals(lastScreen)) {
                XLog.i(TAG, "scrollAndFindAndClick: screen did not change after scroll, reached end of list");
                return false;
            }
            lastScreen = currentScreen;
        }

        return false;
    }

    public static boolean searchOrScrollAndFindAndClick(
        ClawAccessibilityService service,
        String rawQuery,
        LinkedHashSet<String> normalizedAliases,
        LinkedHashSet<String> digitAliases,
        int maxScrolls,
        long settleMs
    ) throws InterruptedException {
        for (int recoveryAttempt = 0; recoveryAttempt < 3; recoveryAttempt++) {
            SearchAttemptResult searchResult = trySearchAndClick(service, rawQuery, normalizedAliases, digitAliases, settleMs);
            if (searchResult == SearchAttemptResult.FOUND) {
                return true;
            }
            if (searchResult == SearchAttemptResult.NO_MATCH) {
                break;
            }

            XLog.i(TAG, "searchOrScrollAndFindAndClick: recovering from " + searchResult + " attempt=" + (recoveryAttempt + 1));
            String activePackage = activePackageName(service);
            if (activePackage == null || activePackage.isEmpty()) {
                break;
            }

            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            Thread.sleep(Math.max(settleMs, 700L));
            if (!prepareForContactLookup(service, activePackage, 2, settleMs)) {
                break;
            }
        }
        return scrollAndFindAndClick(service, normalizedAliases, digitAliases, maxScrolls, settleMs);
    }

    public static AccessibilityNodeInfo findBestVisibleContactNode(
        AccessibilityNodeInfo root,
        Set<String> normalizedAliases,
        Set<String> digitAliases
    ) {
        if (root == null) return null;

        List<AccessibilityNodeInfo> matches = new ArrayList<>();
        collectNodesWithText(root, normalizedAliases, digitAliases, matches);
        if (matches.isEmpty()) return null;

        AccessibilityNodeInfo bestTextMatch = null;
        AccessibilityNodeInfo bestDescMatch = null;
        int bestTextScore = Integer.MIN_VALUE;
        int bestDescScore = Integer.MIN_VALUE;

        for (AccessibilityNodeInfo node : matches) {
            if (!node.isVisibleToUser()) continue;

            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            int score = scoreCandidate(node, bounds);

            if (ContactMatchUtils.matchesCandidate(
                node.getText() != null ? node.getText().toString() : null,
                normalizedAliases,
                digitAliases
            )) {
                if (score > bestTextScore) {
                    bestTextScore = score;
                    bestTextMatch = node;
                }
            } else if (score > bestDescScore) {
                bestDescScore = score;
                bestDescMatch = node;
            }
        }

        return bestTextMatch != null ? bestTextMatch : bestDescMatch;
    }

    private static int scoreCandidate(AccessibilityNodeInfo node, Rect bounds) {
        int score = 0;
        if (node.isClickable()) score += 20;
        if (node.getText() != null && node.getText().length() > 0) score += 25;
        if (node.getContentDescription() != null && node.getContentDescription().length() > 0) score += 5;
        if (bounds.centerY() > 0) score += Math.min(bounds.centerY() / 10, 80);
        return score;
    }

    private static void collectNodesWithText(
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
            if (child != null) {
                collectNodesWithText(child, normalizedAliases, digitAliases, results);
            }
        }
    }

    private static SearchAttemptResult trySearchAndClick(
        ClawAccessibilityService service,
        String rawQuery,
        Set<String> normalizedAliases,
        Set<String> digitAliases,
        long settleMs
    ) throws InterruptedException {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            return SearchAttemptResult.SEARCH_UI_MISSING;
        }

        AccessibilityNodeInfo searchField = UiActionMatchUtils.findBestSearchField(root);
        if (searchField == null && dismissBlockingOverlay(service, root, settleMs)) {
            root = service.getRootInActiveWindow();
            searchField = UiActionMatchUtils.findBestSearchField(root);
        }
        boolean tappedSearchAction = false;
        if (searchField == null) {
            AccessibilityNodeInfo searchAction = UiActionMatchUtils.findBestSearchAction(root);
            if (searchAction != null) {
                boolean clicked = service.clickNode(searchAction);
                XLog.i(TAG, "trySearchAndClick: tapped search action, clicked=" + clicked);
                tappedSearchAction = clicked;
                if (clicked) {
                    Thread.sleep(Math.max(settleMs, 500L));
                    root = service.getRootInActiveWindow();
                    searchField = UiActionMatchUtils.findBestSearchField(root);
                }
            }
        }

        if (searchField == null) {
            XLog.i(TAG, "trySearchAndClick: no search field available");
            return tappedSearchAction ? SearchAttemptResult.SEARCH_UI_MISSING : SearchAttemptResult.NO_MATCH;
        }

        if (!setText(searchField, rawQuery)) {
            XLog.i(TAG, "trySearchAndClick: failed to type query into search field");
            return SearchAttemptResult.TYPE_FAILED;
        }

        Thread.sleep(Math.max(settleMs, 600L));

        root = service.getRootInActiveWindow();
        AccessibilityNodeInfo bestMatch = findBestVisibleResultNode(root, normalizedAliases, digitAliases);
        if (bestMatch != null) {
            XLog.i(TAG, "trySearchAndClick: matched filtered result text=" + bestMatch.getText() + " desc=" + bestMatch.getContentDescription());
            return service.clickNode(bestMatch) ? SearchAttemptResult.FOUND : SearchAttemptResult.NO_MATCH;
        }

        XLog.i(TAG, "trySearchAndClick: no filtered result matched query");
        return SearchAttemptResult.NO_MATCH;
    }

    private static String activePackageName(ClawAccessibilityService service) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) {
            return null;
        }
        return root.getPackageName().toString();
    }

    private static boolean setText(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        clearText(node);

        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!success) return false;

        CharSequence currentText = node.getText();
        return currentText != null && currentText.toString().contains(text);
    }

    private static void clearText(AccessibilityNodeInfo node) {
        Bundle clearArgs = new Bundle();
        clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs);
    }

    private static AccessibilityNodeInfo findBestVisibleResultNode(
        AccessibilityNodeInfo root,
        Set<String> normalizedAliases,
        Set<String> digitAliases
    ) {
        AccessibilityNodeInfo candidate = findBestVisibleContactNode(root, normalizedAliases, digitAliases);
        if (candidate == null) return null;

        CharSequence className = candidate.getClassName();
        String classNameString = className != null ? className.toString() : "";
        if (candidate.isEditable() || classNameString.contains("EditText") || classNameString.contains("AutoComplete")) {
            return null;
        }
        return candidate;
    }

    public static boolean isContactLookupReady(AccessibilityNodeInfo root) {
        if (root == null) return false;

        if (UiActionMatchUtils.findBestSearchField(root) != null) return true;

        int[] metrics = new int[3];
        collectVisibleListSignals(root, metrics);
        int visibleTextRows = metrics[0];
        int clickableRows = metrics[1];
        int scrollableContainers = metrics[2];

        return scrollableContainers > 0 && visibleTextRows >= 3 && clickableRows >= 2;
    }

    private static String safeScreenSnapshot(ClawAccessibilityService service) {
        try {
            return service.getScreenTree();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean dismissBlockingOverlay(
        ClawAccessibilityService service,
        AccessibilityNodeInfo root,
        long settleMs
    ) throws InterruptedException {
        AccessibilityNodeInfo closeAction = findBlockingOverlayCloseAction(root);
        if (closeAction == null) {
            return false;
        }
        boolean clicked = service.clickNode(closeAction);
        XLog.i(TAG, "dismissBlockingOverlay: close action clicked=" + clicked);
        if (clicked) {
            Thread.sleep(Math.max(settleMs, 500L));
        }
        return clicked;
    }

    private static boolean tryExposeSearchUi(
        ClawAccessibilityService service,
        AccessibilityNodeInfo root,
        long settleMs
    ) throws InterruptedException {
        if (root == null) return false;
        if (UiActionMatchUtils.findBestSearchField(root) != null) {
            return false;
        }

        AccessibilityNodeInfo searchAction = UiActionMatchUtils.findBestSearchAction(root);
        if (searchAction == null) return false;

        boolean clicked = service.clickNode(searchAction);
        XLog.i(TAG, "tryExposeSearchUi: search action clicked=" + clicked);
        if (clicked) {
            Thread.sleep(Math.max(settleMs, 500L));
        }
        return clicked;
    }

    private static AccessibilityNodeInfo findBlockingOverlayCloseAction(AccessibilityNodeInfo root) {
        if (root == null) return null;

        Rect screenBounds = new Rect();
        root.getBoundsInScreen(screenBounds);
        if (screenBounds.isEmpty()) return null;

        Candidate best = new Candidate();
        collectCloseCandidates(root, screenBounds, best);
        return best.score >= 70 ? best.node : null;
    }

    private static void collectCloseCandidates(
        AccessibilityNodeInfo node,
        Rect screenBounds,
        Candidate best
    ) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) return;

        int score = scoreCloseCandidate(node, screenBounds);
        if (score > best.score) {
            best.score = score;
            best.node = node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectCloseCandidates(child, screenBounds, best);
            }
        }
    }

    private static int scoreCloseCandidate(AccessibilityNodeInfo node, Rect screenBounds) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty() || bounds.width() <= 0 || bounds.height() <= 0) {
            return Integer.MIN_VALUE;
        }

        if (bounds.top > screenBounds.top + (int) (screenBounds.height() * 0.45f)) {
            return Integer.MIN_VALUE;
        }

        String viewId = node.getViewIdResourceName();
        String className = node.getClassName() != null ? node.getClassName().toString() : "";
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String viewIdLower = viewId != null ? viewId.toLowerCase() : "";

        boolean actionable = node.isClickable() || node.isLongClickable();
        if (!actionable) {
            return Integer.MIN_VALUE;
        }

        boolean explicitClose = false;
        if (viewId != null && (viewId.toLowerCase().contains("close") || viewId.toLowerCase().contains("dismiss"))) {
            explicitClose = true;
        }
        if (matchesCloseHint(text) || matchesCloseHint(desc)) {
            explicitClose = true;
        }
        if (!explicitClose) {
            return Integer.MIN_VALUE;
        }

        int score = 10;
        score += 100;
        if (className.contains("ImageButton") || className.contains("ImageView")) {
            score += 20;
        }
        if (bounds.right >= screenBounds.right - (int) (screenBounds.width() * 0.1f)) {
            score += 18;
        }
        if (bounds.top <= screenBounds.top + (int) (screenBounds.height() * 0.2f)) {
            score += 14;
        }
        if (bounds.width() <= screenBounds.width() * 0.25f && bounds.height() <= screenBounds.height() * 0.15f) {
            score += 12;
        }
        return score;
    }

    private static boolean matchesCloseHint(CharSequence value) {
        if (value == null || value.length() == 0) return false;
        for (String hint : CLOSE_HINTS) {
            if (UiTextMatchUtils.matchesRelaxed(value, hint)) {
                return true;
            }
        }
        return false;
    }

    private static final class Candidate {
        private AccessibilityNodeInfo node;
        private int score = Integer.MIN_VALUE;
    }

    private static void collectVisibleListSignals(AccessibilityNodeInfo node, int[] metrics) {
        if (node == null) return;

        if (node.isVisibleToUser()) {
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            String className = node.getClassName() != null ? node.getClassName().toString() : "";
            boolean imageLike = className.contains("Image")
                || className.contains("Photo")
                || className.contains("Thumbnail");
            boolean rowLike = !node.isEditable()
                && !className.contains("EditText")
                && !imageLike
                && !className.contains("Toolbar")
                && !className.contains("ActionBar");
            boolean hasUsableLabel = text != null && text.length() > 0;

            if (rowLike && hasUsableLabel) {
                metrics[0]++;
                if (node.isClickable()) {
                    metrics[1]++;
                }
            }
            if (node.isScrollable()) {
                metrics[2]++;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectVisibleListSignals(child, metrics);
            }
        }
    }
}
