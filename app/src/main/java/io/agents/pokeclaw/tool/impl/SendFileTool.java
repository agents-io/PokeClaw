package io.agents.pokeclaw.tool.impl;

import android.os.Build;
import android.os.Environment;

import androidx.core.content.ContextCompat;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.ClawApplicationKt;
import io.agents.pokeclaw.R;
import io.agents.pokeclaw.channel.Channel;
import io.agents.pokeclaw.channel.ChannelManager;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SendFileTool extends BaseTool {

    @Override
    public String getName() {
        return "send_file";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_send_file);
    }

    @Override
    public String getDescriptionEN() {
        return "Send a file from the device to the user through the current message channel. Provide the absolute file path on the device.";
    }

    @Override
    public String getDescriptionCN() {
        return "将设备上的文件发送给用户。需要提供文件的绝对路径。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("file_path", "string", "Absolute path of the file to send", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        android.content.Context ctx = ClawApplication.Companion.getInstance();
        // 检查存储权限
        if (!hasStoragePermission()) {
            return ToolResult.error(ctx.getString(R.string.tool_no_storage_permission));
        }

        String filePath = requireString(params, "file_path");
        File file = new File(filePath);

        if (!file.exists()) {
            return ToolResult.error(ctx.getString(R.string.tool_file_not_found, filePath));
        }
        if (!file.isFile()) {
            return ToolResult.error(ctx.getString(R.string.tool_not_a_file, filePath));
        }
        if (!file.canRead()) {
            return ToolResult.error(ctx.getString(R.string.tool_no_storage_permission));
        }

        // 获取当前任务的通道和消息ID
        Channel channel = ClawApplicationKt.getAppViewModel().getInProgressTaskChannel();
        String messageId = ClawApplicationKt.getAppViewModel().getInProgressTaskMessageId();

        if (channel == null || messageId.isEmpty()) {
            return ToolResult.error(ctx.getString(R.string.tool_no_task_channel));
        }

        try {
            ChannelManager.sendFile(channel, file, messageId);
            return ToolResult.success(ctx.getString(R.string.tool_file_sent, file.getName()));
        } catch (Exception e) {
            return ToolResult.error(ctx.getString(R.string.tool_file_send_failed, e.getMessage()));
        }
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(
                    ClawApplication.Companion.getInstance(),
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
    }
}
