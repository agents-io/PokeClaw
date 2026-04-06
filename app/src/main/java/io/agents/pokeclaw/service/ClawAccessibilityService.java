// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import io.agents.pokeclaw.utils.XLog;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core accessibility service that provides all device interaction capabilities.
 * Singleton-pattern: the running instance is accessible via {@link #getInstance()}.
 */
public class ClawAccessibilityService extends AccessibilityService {

    private static final String TAG = "ClawA11yService";
    private static volatile ClawAccessibilityService instance;

    public static ClawAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        XLog.i(TAG, "Accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Auto-reply: check incoming messaging notifications
        try {
            AutoReplyManager.getInstance().onAccessibilityEvent(event);
        } catch (Exception e) {
            // Never crash the accessibility service
        }
    }

    @Override
    public void onInterrupt() {
        XLog.w(TAG, "Accessibility service interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        XLog.i(TAG, "Accessibility service destroyed");
    }

    // ======================== Gesture Operations ========================

    /**
     * Performs a tap at the given screen coordinates.
     */
    public boolean performTap(int x, int y) {
        return performTap(x, y, 100);
    }

    public boolean performTap(int x, int y, long durationMs) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, durationMs);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        return dispatchGestureSync(gesture);
    }

    /**
     * Performs a long press at the given screen coordinates.
     */
    public boolean performLongPress(int x, int y, long durationMs) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, durationMs);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        return dispatchGestureSync(gesture);
    }

    /**
     * Performs a swipe gesture from (startX, startY) to (endX, endY).
     */
    public boolean performSwipe(int startX, int startY, int endX, int endY, long durationMs) {
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, durationMs);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        return dispatchGestureSync(gesture);
    }

    /**
     * Dispatches a gesture and waits for it to complete synchronously.
     */
    private boolean dispatchGestureSync(GestureDescription gesture) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(false);

        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                result.set(true);
                latch.countDown();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                result.set(false);
                latch.countDown();
            }
        }, null);

        if (!dispatched) {
            return false;
        }

        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return result.get();
    }

    // ======================== Node Operations ========================

    /**
     * Finds all nodes matching the given text.
     */
    public List<AccessibilityNodeInfo> findNodesByText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return new ArrayList<>();
        }
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        return nodes != null ? nodes : new ArrayList<>();
    }

    /**
     * Finds all nodes matching the given view ID (e.g. "com.example:id/button").
     */
    public List<AccessibilityNodeInfo> findNodesById(String viewId) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return new ArrayList<>();
        }
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
        return nodes != null ? nodes : new ArrayList<>();
    }

    /**
     * Clicks on a node.
     */
    public boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        if (node.isClickable()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        // Try clicking the parent if the node itself is not clickable
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.isClickable()) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            parent = parent.getParent();
        }
        // Fallback: tap at center of node bounds
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return performTap(bounds.centerX(), bounds.centerY());
    }

    /**
     * Sets text on a node (for EditText fields).
     */
    public boolean setNodeText(AccessibilityNodeInfo node, String text) {
        if (node == null) {
            return false;
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    /**
     * Collects a tree representation of the current screen for AI analysis.
     */
    public String getScreenTree() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        buildNodeTree(root, sb, 0);
        return sb.toString();
    }

    /**
     * Collects a FULL tree representation of the current screen (debug only).
     * Includes ALL nodes with all properties, no filtering.
     * Useful for comparing with the filtered version to debug AI behavior.
     */
    public String getScreenTreeFull() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        buildNodeTreeFull(root, sb, 0);
        return sb.toString();
    }

    private void buildNodeTree(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null) {
            return;
        }

        // 跳过不在屏幕可见区域内的节点（滚动容器中超出屏幕的元素）
        if (!node.isVisibleToUser()) {
            // 仍然遍历子节点，因为父节点不可见不代表所有子节点都不可见
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    buildNodeTree(child, sb, depth);
                    child.recycle();
                }
            }
            return;
        }

        // 判断当前节点是否有"信息量"（有 text/desc/可交互/可滚动/可编辑/进度条/滑块）
        boolean hasText = node.getText() != null && node.getText().length() > 0;
        boolean hasDesc = node.getContentDescription() != null && node.getContentDescription().length() > 0;
        boolean isInteractive = node.isClickable() || node.isScrollable() || node.isEditable()
                || node.isCheckable() || node.isLongClickable();
        boolean isSlider = isSliderNode(node);
        CharSequence cn = node.getClassName();
        boolean isProgress = cn != null && cn.toString().contains("ProgressBar");
        boolean isMeaningful = hasText || hasDesc || isInteractive || isSlider || isProgress;

        if (isMeaningful) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            // Ultra-compact format: "text" [flags] (cx,cy)
            // Center coordinates instead of full bounds — LLM only needs tap target
            int cx = (bounds.left + bounds.right) / 2;
            int cy = (bounds.top + bounds.bottom) / 2;

            // Add indentation for hierarchy context
            StringBuilder line = new StringBuilder();
            for (int d = 0; d < Math.min(depth, 4); d++) line.append("  ");

            if (hasText) {
                CharSequence text = node.getText();
                line.append("\"").append(text.length() > 40 ? text.subSequence(0, 40) + ".." : text).append("\"");
            } else if (hasDesc) {
                line.append("\"").append(node.getContentDescription()).append("\"");
            }
            if (node.isClickable()) line.append(" tap");
            if (node.isEditable()) line.append(" edit");
            if (node.isScrollable()) line.append(" scroll");
            if (node.isCheckable()) line.append(node.isChecked() ? " on" : " off");
            line.append(" (").append(cx).append(",").append(cy).append(")");

            // Only output if has any content
            if (line.length() > 0) {
                sb.append(line).append("\n");
            }
        }

        // 子节点层级：如果当前节点被跳过（非 meaningful），子节点保持同层级，不增加 depth
        int childDepth = isMeaningful ? depth + 1 : depth;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                buildNodeTree(child, sb, childDepth);
                child.recycle();
            }
        }
    }

    /**
     * Full node tree builder - outputs ALL nodes with ALL properties, no filtering.
     */
    private void buildNodeTreeFull(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null) {
            return;
        }

        String indent = "  ".repeat(depth);
        sb.append(indent);

        // className
        CharSequence className = node.getClassName();
        if (className != null) {
            String cls = className.toString();
            int dotIdx = cls.lastIndexOf('.');
            sb.append("[").append(dotIdx >= 0 ? cls.substring(dotIdx + 1) : cls).append("]");
        }

        // text
        if (node.getText() != null && node.getText().length() > 0) {
            CharSequence text = node.getText();
            if (text.length() > 200) {
                sb.append(" text=\"").append(text.subSequence(0, 200)).append("...\"");
            } else {
                sb.append(" text=\"").append(text).append("\"");
            }
        }

        // contentDescription
        if (node.getContentDescription() != null && node.getContentDescription().length() > 0) {
            sb.append(" desc=\"").append(node.getContentDescription()).append("\"");
        }

        // resource-id
        String resId = node.getViewIdResourceName();
        if (resId != null && !resId.isEmpty()) {
            sb.append(" id=\"").append(resId).append("\"");
        }

        // package
        if (node.getPackageName() != null) {
            sb.append(" pkg=\"").append(node.getPackageName()).append("\"");
        }

        // interaction states
        if (node.isClickable()) sb.append(" [clickable]");
        if (node.isLongClickable()) sb.append(" [long-clickable]");
        if (node.isScrollable()) sb.append(" [scrollable]");
        if (node.isEditable()) sb.append(" [editable]");
        if (node.isCheckable()) sb.append(node.isChecked() ? " [checked]" : " [unchecked]");
        if (!node.isEnabled()) sb.append(" [disabled]");
        if (node.isFocused()) sb.append(" [focused]");
        if (node.isSelected()) sb.append(" [selected]");
        if (!node.isVisibleToUser()) sb.append(" [invisible]");

        // slider range info
        if (isSliderNode(node)) {
            sb.append(" [slider]");
            AccessibilityNodeInfo.RangeInfo rangeInfo = node.getRangeInfo();
            if (rangeInfo != null) {
                sb.append(String.format(" range=[%.0f-%.0f, current=%.0f]",
                        rangeInfo.getMin(), rangeInfo.getMax(), rangeInfo.getCurrent()));
            }
        }

        // progress bar
        CharSequence cn = node.getClassName();
        if (cn != null && cn.toString().contains("ProgressBar")) {
            sb.append(" [loading]");
        }

        // bounds
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        sb.append(" bounds=").append(bounds.toShortString());

        sb.append("\n");

        // recurse all children
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                buildNodeTreeFull(child, sb, depth + 1);
                child.recycle();
            }
        }
    }

    /**
     * Recycles a list of AccessibilityNodeInfo nodes.
     * Call this after you are done using nodes returned by findNodesByText/findNodesById.
     */
    public static void recycleNodes(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return;
        for (AccessibilityNodeInfo node : nodes) {
            if (node != null) {
                try {
                    node.recycle();
                } catch (Exception ignored) {
                    // Already recycled
                }
            }
        }
    }

    /**
     * Finds a specific node and returns detailed info as a string.
     */
    public String getNodeDetail(AccessibilityNodeInfo node) {
        if (node == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("class=").append(node.getClassName());
        if (node.getText() != null) {
            sb.append(", text=\"").append(node.getText()).append("\"");
        }
        if (node.getContentDescription() != null) {
            sb.append(", desc=\"").append(node.getContentDescription()).append("\"");
        }
        sb.append(", clickable=").append(node.isClickable());
        sb.append(", enabled=").append(node.isEnabled());
        sb.append(", visible=").append(node.isVisibleToUser());
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        sb.append(", bounds=").append(bounds.toShortString());
        return sb.toString();
    }

    // ======================== Slider Detection (for buildNodeTree) ========================

    /**
     * Check if a node is a slider/seekbar type.
     * Used by buildNodeTree to ensure slider nodes are included in screen info.
     */
    private boolean isSliderNode(AccessibilityNodeInfo node) {
        CharSequence className = node.getClassName();
        if (className == null) return false;
        String cls = className.toString();
        return cls.contains("SeekBar")
                || cls.contains("Slider")
                || cls.contains("RatingBar")
                || node.getRangeInfo() != null;
    }

    // ======================== Global Actions ========================

    public boolean pressBack() {
        return performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public boolean pressHome() {
        return performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public boolean openRecentApps() {
        return performGlobalAction(GLOBAL_ACTION_RECENTS);
    }

    public boolean expandNotifications() {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
    }

    public boolean collapseNotifications() {
        return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
    }

    public boolean lockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        }
        return false;
    }

    /**
     * Attempts to unlock the screen: wake up + swipe up.
     * Works for no-password / swipe lock screens.
     * If the device has PIN/pattern/password, the swipe will bring up the input screen.
     */
    public boolean unlockScreen() {
        try {
            // 1. 唤醒屏幕
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                @SuppressWarnings("deprecation")
                android.os.PowerManager.WakeLock wl = pm.newWakeLock(
                        android.os.PowerManager.SCREEN_DIM_WAKE_LOCK | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "PokeClaw:unlock"
                );
                wl.acquire(3000);
                wl.release();
                // 等屏幕亮起
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }

            // 2. 模拟上滑手势解锁
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int centerX = dm.widthPixels / 2;
            int bottomY = (int) (dm.heightPixels * 0.8);
            int topY = (int) (dm.heightPixels * 0.2);
            return performSwipe(centerX, bottomY, centerX, topY, 300);
        } catch (Exception e) {
            XLog.e(TAG, "unlockScreen failed", e);
            return false;
        }
    }

    // ======================== Screenshot ========================

    /**
     * Takes a screenshot (requires API 30+).
     * Returns the bitmap or null on failure.
     */
    public Bitmap takeScreenshot(long timeoutMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bitmap> bitmapRef = new AtomicReference<>(null);

        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult result) {
                        Bitmap bmp = Bitmap.wrapHardwareBuffer(
                                result.getHardwareBuffer(), result.getColorSpace());
                        bitmapRef.set(bmp);
                        result.getHardwareBuffer().close();
                        latch.countDown();
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        XLog.e(TAG, "Screenshot failed with error code: " + errorCode);
                        latch.countDown();
                    }
                });

        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return bitmapRef.get();
    }

    // ======================== Key Event Injection (TV Remote) ========================

    /**
     * Sends a key event via shell command. Works reliably on Android TV boxes.
     *
     * @param keyCode Android KeyEvent keycode (e.g. KeyEvent.KEYCODE_DPAD_UP = 19)
     * @return true if the command executed without error
     */
    public boolean sendKeyEvent(int keyCode) {
        try {
            Process process = Runtime.getRuntime().exec(
                    new String[]{"input", "keyevent", String.valueOf(keyCode)});
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            XLog.e(TAG, "Failed to send key event: " + keyCode, e);
            return false;
        }
    }

    // ======================== App Launch ========================

    /**
     * Opens an app by its package name.
     */
    public boolean openApp(String packageName) {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null) {
                XLog.e(TAG, "Cannot resolve launch intent for " + packageName);
                return false;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (Exception e) {
            XLog.e(TAG, "Failed to open app: " + packageName, e);
            return false;
        }
    }
}
