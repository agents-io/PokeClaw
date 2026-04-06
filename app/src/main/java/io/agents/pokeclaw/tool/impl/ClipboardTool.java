package io.agents.pokeclaw.tool.impl;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ClipboardTool extends BaseTool {

    @Override
    public String getName() {
        return "clipboard";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_clipboard);
    }

    @Override
    public String getDescriptionEN() {
        return "Get or set the clipboard text content. Use action 'get' to read, 'set' to write.";
    }

    @Override
    public String getDescriptionCN() {
        return "获取或设置剪贴板文本内容。action 为 'get' 读取，'set' 写入。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("action", "string", "The action to perform: 'get' or 'set'", true),
                new ToolParameter("text", "string", "The text to set (required when action is 'set')", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String action = requireString(params, "action");
        switch (action) {
            case "get":
                return executeGet();
            case "set":
                String text = requireString(params, "text");
                return executeSet(text);
            default:
                return ToolResult.error("Unknown action: " + action + ", expected 'get' or 'set'");
        }
    }

    private ToolResult executeGet() {
        try {
            Context context = ClawApplication.Companion.getInstance();

            ClipboardReaderActivity.prepare();
            Intent intent = new Intent(context, ClipboardReaderActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);

            boolean completed = ClipboardReaderActivity.latch.await(3, TimeUnit.SECONDS);
            if (!completed) {
                return ToolResult.error("Clipboard read timed out");
            }

            if (ClipboardReaderActivity.clipboardResult != null) {
                return ToolResult.success(ClipboardReaderActivity.clipboardResult);
            }
            if (ClipboardReaderActivity.clipboardError != null) {
                return ToolResult.error(ClipboardReaderActivity.clipboardError);
            }
            return ToolResult.error("Clipboard is empty");
        } catch (Exception e) {
            return ToolResult.error("Failed to read clipboard: " + e.getMessage());
        }
    }

    private ToolResult executeSet(String text) {
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] result = {false};

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Context context = ClawApplication.Companion.getInstance();
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("clipboard_tool", text));
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

        return result[0]
                ? ToolResult.success("Clipboard text set successfully")
                : ToolResult.error("Failed to set clipboard text");
    }
}
