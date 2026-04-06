package io.agents.pokeclaw.tool.impl;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class InputTextTool extends BaseTool {

    @Override
    public String getName() {
        return "input_text";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_input_text);
    }

    @Override
    public String getDescriptionEN() {
        return "Input text into the currently focused text field. "
                + "Tap the text field first to focus it, then call this tool. "
                + "By default clears existing content before inputting (clear_first=true). "
                + "Set clear_first=false to append text without clearing.";
    }

    @Override
    public String getDescriptionCN() {
        return "向当前聚焦的文本框输入文本。先 tap 文本框使其获得焦点，再调用此工具。"
                + "默认先清空已有内容再输入（clear_first=true）。"
                + "设置 clear_first=false 可追加文本而不清空。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("text", "string", "The text to input", true),
                new ToolParameter("clear_first", "boolean", "Whether to clear existing text before input (default true)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = ClawAccessibilityService.getInstance();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }

        String text = requireString(params, "text");
        boolean clearFirst = optionalBoolean(params, "clear_first", true);

        AccessibilityNodeInfo targetNode = service.getRootInActiveWindow() != null
                ? findFocusedEditText(service.getRootInActiveWindow())
                : null;

        if (targetNode == null) {
            return ToolResult.error("No target text field found");
        }

        // 先尝试点击获取焦点
        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);

        // 如果需要清空，先全选+删除
        if (clearFirst) {
            clearNodeText(targetNode);
        }

        // 策略1: 先尝试 ACTION_SET_TEXT（标准方式）
        // 注意：ACTION_SET_TEXT 本身是覆盖式的，append 模式下需要拼接原有文本
        if (clearFirst) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            if (targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return ToolResult.success("Input text: " + text);
            }
        } else {
            // append 模式：读取已有文本 + 新文本
            CharSequence existing = targetNode.getText();
            String newText = (existing != null ? existing.toString() : "") + text;
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText);
            if (targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return ToolResult.success("Appended text: " + text);
            }
        }

        // 策略2: 通过剪贴板粘贴（兼容性更好）
        boolean clipboardSet = setClipboardText(service, text);
        if (!clipboardSet) {
            return ToolResult.error("Failed to set clipboard text");
        }

        if (clearFirst) {
            // 再次确保清空（有些 App 策略1失败后可能没清干净）
            clearNodeText(targetNode);
        } else {
            // append 模式：光标移到末尾
            CharSequence existing = targetNode.getText();
            int end = existing != null ? existing.length() : 0;
            Bundle cursorArgs = new Bundle();
            cursorArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, end);
            cursorArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end);
            targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs);
        }

        // 执行粘贴
        if (targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            return ToolResult.success(clearFirst ? "Input text (via paste): " + text : "Appended text (via paste): " + text);
        }

        return ToolResult.error("Failed to input text, both ACTION_SET_TEXT and clipboard paste failed");
    }

    /**
     * 清空输入框内容：全选 → 删除
     */
    private void clearNodeText(AccessibilityNodeInfo node) {
        // 全选
        Bundle selectAllArgs = new Bundle();
        selectAllArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
        selectAllArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Integer.MAX_VALUE);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs);

        // 用空字符串覆盖选中内容
        Bundle clearArgs = new Bundle();
        clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs);
    }

    private boolean setClipboardText(Context context, String text) {
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] result = {false};

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("input_text", text));
                    result[0] = true;
                }
            } catch (Exception ignored) {
            }
            latch.countDown();
        });

        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return result[0];
    }

    private AccessibilityNodeInfo findFocusedEditText(AccessibilityNodeInfo root) {
        if (root == null) return null;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null && focused.isEditable()) {
            return focused;
        }
        // Fallback: find first editable node
        return findFirstEditable(root);
    }

    private AccessibilityNodeInfo findFirstEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo result = findFirstEditable(child);
            if (result != null) {
                // Don't recycle child if it's the result itself
                if (result != child) {
                    child.recycle();
                }
                return result;
            }
            child.recycle();
        }
        return null;
    }
}
