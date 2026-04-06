// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl.mobile;

import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;
import io.agents.pokeclaw.service.ClawAccessibilityService;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 滚动查找工具：在当前页面自动滚动并查找包含指定文本的元素。
 * 找到后返回元素的坐标信息，避免 LLM 反复调用 get_screen_info + swipe 的循环。
 */
public class ScrollToFindTool extends BaseTool {

    @Override
    public String getName() {
        return "scroll_to_find";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_scroll_to_find);
    }

    @Override
    public String getDescriptionEN() {
        return "Scroll the screen to find an element containing the specified text. "
                + "Automatically scrolls in the given direction and searches after each scroll. "
                + "Returns the element's bounds and center coordinates if found. "
                + "Much more efficient than manually calling swipe + get_screen_info in a loop.";
    }

    @Override
    public String getDescriptionCN() {
        return "滚动屏幕查找包含指定文本的元素。自动在指定方向上滚动并在每次滚动后搜索。"
                + "找到后返回元素的边界和中心坐标。比手动循环调用 swipe + get_screen_info 高效得多。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("text", "string", "The text to search for on the screen", true),
                new ToolParameter("direction", "string",
                        "Scroll direction: 'up' or 'down' (default 'down'). 'down' means content moves up to reveal lower content.", false),
                new ToolParameter("max_scrolls", "integer",
                        "Maximum number of scrolls to attempt (default 10, max 20)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = ClawAccessibilityService.getInstance();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }

        String text = requireString(params, "text");
        String direction = optionalString(params, "direction", "down");
        int maxScrolls = optionalInt(params, "max_scrolls", 10);
        maxScrolls = Math.min(Math.max(maxScrolls, 1), 20);

        // 获取屏幕尺寸
        int[] screenSize = getScreenSize();
        int screenWidth = screenSize[0];
        int screenHeight = screenSize[1];

        // 滚动参数：在屏幕中间区域滚动，避开顶部状态栏和底部导航栏
        int centerX = screenWidth / 2;
        int scrollStartY, scrollEndY;
        if ("up".equals(direction)) {
            // 向上滚动（内容向下移）：从上往下滑
            scrollStartY = (int) (screenHeight * 0.3);
            scrollEndY = (int) (screenHeight * 0.7);
        } else {
            // 向下滚动（内容向上移）：从下往上滑
            scrollStartY = (int) (screenHeight * 0.7);
            scrollEndY = (int) (screenHeight * 0.3);
        }

        // 先在当前屏幕查找（不滚动）
        ToolResult found = findElement(service, text);
        if (found != null) {
            return found;
        }

        // 循环：滚动 → 查找
        String lastScreenContent = getScreenSnapshot(service);
        for (int i = 0; i < maxScrolls; i++) {
            // 执行滑动
            boolean swiped = service.performSwipe(centerX, scrollStartY, centerX, scrollEndY, 400);
            if (!swiped) {
                return ToolResult.error("Swipe failed at scroll #" + (i + 1));
            }

            // 等待页面稳定
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error("Interrupted during scroll");
            }

            // 查找目标
            found = findElement(service, text);
            if (found != null) {
                return found;
            }

            // 检测是否到底/到顶（屏幕内容不再变化）
            String currentScreen = getScreenSnapshot(service);
            if (currentScreen != null && currentScreen.equals(lastScreenContent)) {
                return ToolResult.error("Element with text \"" + text + "\" not found. "
                        + "Reached the " + ("up".equals(direction) ? "top" : "bottom")
                        + " after " + (i + 1) + " scroll(s).");
            }
            lastScreenContent = currentScreen;
        }

        return ToolResult.error("Element with text \"" + text + "\" not found after " + maxScrolls + " scroll(s).");
    }

    /**
     * 在当前屏幕查找包含指定文本的元素，找到返回 ToolResult，没找到返回 null。
     */
    private ToolResult findElement(ClawAccessibilityService service, String text) {
        List<AccessibilityNodeInfo> nodes = service.findNodesByText(text);
        if (nodes.isEmpty()) {
            return null;
        }
        try {
            // 取第一个可见的节点
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isVisibleToUser()) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    int centerX = bounds.centerX();
                    int centerY = bounds.centerY();
                    StringBuilder sb = new StringBuilder();
                    sb.append("Found element with text \"").append(text).append("\"");
                    sb.append("\n  bounds=").append(bounds.toShortString());
                    sb.append("\n  center=(").append(centerX).append(", ").append(centerY).append(")");
                    sb.append("\n  clickable=").append(node.isClickable());
                    if (node.getClassName() != null) {
                        sb.append("\n  class=").append(node.getClassName());
                    }
                    return ToolResult.success(sb.toString());
                }
            }
            // 所有节点都不可见
            return null;
        } finally {
            ClawAccessibilityService.recycleNodes(nodes);
        }
    }

    /**
     * 快速获取屏幕内容的摘要，用于判断页面是否滚动到底/顶。
     */
    private String getScreenSnapshot(ClawAccessibilityService service) {
        try {
            return service.getScreenTree();
        } catch (Exception e) {
            return null;
        }
    }

    // getScreenSize() 由 BaseTool 提供
}
