// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;
import io.agents.pokeclaw.service.ClawAccessibilityService;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SystemKeyTool extends BaseTool {

    @Override
    public String getName() {
        return "system_key";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_system_key);
    }

    @Override
    public String getDescriptionEN() {
        return "Press a system key. Supported keys: back (navigate back), home (go to home screen), recent_apps (open task switcher), notifications (expand notification shade), collapse_notifications (collapse notification/quick settings), lock_screen (lock screen, Android 9+), unlock_screen (wake up and unlock screen).";
    }

    @Override
    public String getDescriptionCN() {
        return "按下系统按键。支持的按键：back（返回）、home（回到桌面）、recent_apps（打开最近任务）、notifications（展开通知栏）、collapse_notifications（收起通知栏/快捷设置）、lock_screen（锁屏，需Android 9+）、unlock_screen（唤醒并解锁屏幕）。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter(
                        "key",
                        "string",
                        "The system key to press. Must be one of: back, home, recent_apps, notifications, collapse_notifications, lock_screen, unlock_screen.",
                        true
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = ClawAccessibilityService.getInstance();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }

        String key = requireString(params, "key");
        boolean success;
        String successMsg;

        switch (key) {
            case "back":
                success = service.pressBack();
                successMsg = "Pressed Back button";
                break;
            case "home":
                success = service.pressHome();
                successMsg = "Pressed Home button";
                break;
            case "recent_apps":
                success = service.openRecentApps();
                successMsg = "Opened recent apps";
                break;
            case "notifications":
                success = service.expandNotifications();
                successMsg = "Expanded notifications";
                break;
            case "collapse_notifications":
                success = service.collapseNotifications();
                successMsg = "Collapsed notifications";
                break;
            case "lock_screen":
                success = service.lockScreen();
                successMsg = "Screen locked";
                break;
            case "unlock_screen":
                success = service.unlockScreen();
                successMsg = "Screen unlock requested";
                break;
            default:
                return ToolResult.error("Unknown system key: " + key + ". Must be one of: back, home, recent_apps, notifications, collapse_notifications, lock_screen, unlock_screen.");
        }

        return success ? ToolResult.success(successMsg)
                : ToolResult.error("Failed to execute " + key);
    }
}
