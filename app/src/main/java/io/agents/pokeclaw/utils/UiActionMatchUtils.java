// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.utils;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Shared structural heuristics for common accessibility action buttons.
 *
 * This keeps deterministic paths generic:
 * - prefer stable structure / ids / geometry first
 * - use visible text or descriptions only as a fallback signal
 * - avoid English-only exact-text assumptions for common actions like "send"
 */
public final class UiActionMatchUtils {
    private static final String[] SEND_ID_HINTS = {
            "send", "reply", "submit", "done", "fab"
    };
    private static final String[] SEARCH_ID_HINTS = {
            "search", "find", "query"
    };

    private static final String[] SEND_TEXT_HINTS = {
            "send", "發送", "发送", "傳送", "전송", "送信", "enviar", "envoyer", "senden", "отправить"
    };
    private static final String[] SEARCH_TEXT_HINTS = {
            "search", "搜尋", "搜索", "查找", "查詢", "検索", "찾기", "buscar", "rechercher", "suche", "искать"
    };

    private UiActionMatchUtils() {}

    public static AccessibilityNodeInfo findBestSendAction(AccessibilityNodeInfo root, Rect anchorBounds) {
        if (root == null) return null;

        Rect screenBounds = new Rect();
        root.getBoundsInScreen(screenBounds);

        Candidate best = new Candidate();
        collectSendCandidates(root, anchorBounds, screenBounds, best);
        return best.score >= 60 ? best.node : null;
    }

    public static AccessibilityNodeInfo findBestSearchAction(AccessibilityNodeInfo root) {
        if (root == null) return null;

        Rect screenBounds = new Rect();
        root.getBoundsInScreen(screenBounds);

        Candidate best = new Candidate();
        collectSearchActionCandidates(root, screenBounds, best);
        return best.score >= 70 ? best.node : null;
    }

    public static AccessibilityNodeInfo findBestSearchField(AccessibilityNodeInfo root) {
        if (root == null) return null;

        Rect screenBounds = new Rect();
        root.getBoundsInScreen(screenBounds);

        Candidate best = new Candidate();
        collectSearchFieldCandidates(root, screenBounds, best);
        return best.score >= 40 ? best.node : null;
    }

    private static void collectSendCandidates(
            AccessibilityNodeInfo node,
            Rect anchorBounds,
            Rect screenBounds,
            Candidate best
    ) {
        if (node == null) return;

        int score = scoreSendCandidate(node, anchorBounds, screenBounds);
        if (score > best.score) {
            best.score = score;
            best.node = node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectSendCandidates(child, anchorBounds, screenBounds, best);
            }
        }
    }

    private static void collectSearchActionCandidates(
            AccessibilityNodeInfo node,
            Rect screenBounds,
            Candidate best
    ) {
        if (node == null) return;

        int score = scoreSearchActionCandidate(node, screenBounds);
        if (score > best.score) {
            best.score = score;
            best.node = node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectSearchActionCandidates(child, screenBounds, best);
            }
        }
    }

    private static void collectSearchFieldCandidates(
            AccessibilityNodeInfo node,
            Rect screenBounds,
            Candidate best
    ) {
        if (node == null) return;

        int score = scoreSearchFieldCandidate(node, screenBounds);
        if (score > best.score) {
            best.score = score;
            best.node = node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectSearchFieldCandidates(child, screenBounds, best);
            }
        }
    }

    private static int scoreSendCandidate(
            AccessibilityNodeInfo node,
            Rect anchorBounds,
            Rect screenBounds
    ) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) {
            return Integer.MIN_VALUE;
        }

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty() || bounds.width() <= 0 || bounds.height() <= 0) {
            return Integer.MIN_VALUE;
        }

        int screenArea = Math.max(1, screenBounds.width() * screenBounds.height());
        int area = bounds.width() * bounds.height();
        if (area > screenArea / 3) {
            return Integer.MIN_VALUE;
        }

        boolean actionable = node.isClickable() || node.isLongClickable();
        String className = node.getClassName() != null ? node.getClassName().toString() : "";
        String viewId = node.getViewIdResourceName();
        CharSequence desc = node.getContentDescription();
        CharSequence text = node.getText();
        boolean hasExplicitSendSignal = containsIdHint(viewId)
                || containsTextHint(desc)
                || containsTextHint(text);
        boolean nearComposerAnchor = false;

        int score = 0;

        if (actionable) score += 20;

        if (className.contains("ImageButton") || className.contains("FloatingActionButton")) {
            score += 35;
        } else if (className.contains("Button")) {
            score += 25;
        } else if (className.contains("ImageView")) {
            score += 10;
        } else if (className.contains("TextView")) {
            score += 5;
        }

        if (containsIdHint(viewId)) {
            score += 120;
        }

        if (containsTextHint(desc) || containsTextHint(text)) {
            score += 80;
        }

        if (anchorBounds != null && !anchorBounds.isEmpty()) {
            int centerX = bounds.centerX();
            int centerY = bounds.centerY();
            int anchorHeight = Math.max(anchorBounds.height(), 1);
            boolean verticallyNearAnchor = centerY >= anchorBounds.top - anchorHeight
                    && centerY <= anchorBounds.bottom + (anchorHeight * 2);
            boolean rightOfComposer = centerX >= anchorBounds.centerX();

            if (verticallyNearAnchor) {
                score += 20;
            }
            if (rightOfComposer) {
                score += 18;
            }
            if (bounds.left >= anchorBounds.left) {
                score += 10;
            }
            if (bounds.top <= anchorBounds.bottom + anchorHeight) {
                score += 10;
            }
            nearComposerAnchor = verticallyNearAnchor && rightOfComposer;
        }

        if (!screenBounds.isEmpty()) {
            if (bounds.centerX() >= screenBounds.centerX()) score += 6;
            if (bounds.centerY() >= screenBounds.centerY()) score += 6;
        }

        if (!hasExplicitSendSignal && !nearComposerAnchor) {
            return Integer.MIN_VALUE;
        }

        if (!actionable && score < 80) {
            return Integer.MIN_VALUE;
        }

        return score;
    }

    private static int scoreSearchActionCandidate(
            AccessibilityNodeInfo node,
            Rect screenBounds
    ) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) {
            return Integer.MIN_VALUE;
        }

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty() || bounds.width() <= 0 || bounds.height() <= 0) {
            return Integer.MIN_VALUE;
        }

        if (!screenBounds.isEmpty() && bounds.top > screenBounds.top + (int) (screenBounds.height() * 0.35f)) {
            return Integer.MIN_VALUE;
        }

        boolean actionable = node.isClickable() || node.isLongClickable();
        if (!actionable) return Integer.MIN_VALUE;

        String className = node.getClassName() != null ? node.getClassName().toString() : "";
        String viewId = node.getViewIdResourceName();
        CharSequence desc = node.getContentDescription();
        CharSequence text = node.getText();

        int score = 0;
        score += 20;

        if (className.contains("ImageButton") || className.contains("FloatingActionButton")) {
            score += 35;
        } else if (className.contains("Button")) {
            score += 25;
        } else if (className.contains("ImageView")) {
            score += 12;
        }

        if (containsSearchIdHint(viewId)) {
            score += 120;
        }
        if (containsSearchTextHint(desc) || containsSearchTextHint(text)) {
            score += 80;
        }

        if (!screenBounds.isEmpty()) {
            if (bounds.centerX() >= screenBounds.centerX()) score += 18;
            if (bounds.top <= screenBounds.top + (int) (screenBounds.height() * 0.2f)) score += 10;
        }

        return score;
    }

    private static int scoreSearchFieldCandidate(
            AccessibilityNodeInfo node,
            Rect screenBounds
    ) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) {
            return Integer.MIN_VALUE;
        }

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty() || bounds.width() <= 0 || bounds.height() <= 0) {
            return Integer.MIN_VALUE;
        }

        if (!screenBounds.isEmpty() && bounds.top > screenBounds.top + (int) (screenBounds.height() * 0.4f)) {
            return Integer.MIN_VALUE;
        }

        String className = node.getClassName() != null ? node.getClassName().toString() : "";
        String viewId = node.getViewIdResourceName();
        CharSequence desc = node.getContentDescription();
        CharSequence text = node.getText();
        CharSequence hint = node.getHintText();

        boolean editable = node.isEditable()
                || className.contains("EditText")
                || className.contains("AutoComplete")
                || className.contains("TextInput");

        if (!editable) return Integer.MIN_VALUE;

        int score = 20;
        if (!screenBounds.isEmpty() && bounds.width() >= (int) (screenBounds.width() * 0.25f)) {
            score += 15;
        }
        if (!screenBounds.isEmpty() && bounds.width() >= (int) (screenBounds.width() * 0.5f)) {
            score += 10;
        }
        if (!screenBounds.isEmpty() && bounds.top <= screenBounds.top + (int) (screenBounds.height() * 0.22f)) {
            score += 10;
        }
        if (!screenBounds.isEmpty() && bounds.left <= screenBounds.left + (int) (screenBounds.width() * 0.2f)) {
            score += 5;
        }
        if (containsSearchIdHint(viewId)) score += 120;
        if (containsSearchTextHint(desc) || containsSearchTextHint(text) || containsSearchTextHint(hint)) {
            score += 80;
        }

        return score;
    }

    private static boolean containsIdHint(String value) {
        if (value == null || value.isEmpty()) return false;
        String normalized = value.toLowerCase();
        for (String hint : SEND_ID_HINTS) {
            if (normalized.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSearchIdHint(String value) {
        if (value == null || value.isEmpty()) return false;
        String normalized = value.toLowerCase();
        for (String hint : SEARCH_ID_HINTS) {
            if (normalized.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTextHint(CharSequence value) {
        if (value == null || value.length() == 0) return false;
        for (String hint : SEND_TEXT_HINTS) {
            if (UiTextMatchUtils.matchesRelaxed(value, hint)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSearchTextHint(CharSequence value) {
        if (value == null || value.length() == 0) return false;
        for (String hint : SEARCH_TEXT_HINTS) {
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
}
