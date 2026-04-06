// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 获取设备上已安装的可启动应用列表（应用名 + 包名）。
 * 当不确定目标应用的包名时，先调用此工具获取列表，再使用 open_app 打开。
 */
public class GetInstalledAppsTool extends BaseTool {

    @Override
    public String getName() {
        return "get_installed_apps";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_get_installed_apps);
    }

    @Override
    public String getDescriptionEN() {
        return "Get a list of all installed launchable apps on the device, including app name and package name. Use this when you don't know the exact package name of an app, then use open_app to launch it.";
    }

    @Override
    public String getDescriptionCN() {
        return "获取设备上所有已安装的可启动应用列表，包括应用名和包名。当不确定目标应用的包名时使用此工具，然后使用 open_app 打开。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("keyword", "string",
                        "Optional keyword to filter apps by name (case-insensitive). If empty, returns all apps.", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String keyword = optionalString(params, "keyword", "");

        try {
            PackageManager pm = ClawApplication.Companion.getInstance().getPackageManager();
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

            List<ResolveInfo> resolveInfos = pm.queryIntentActivities(mainIntent, 0);
            if (resolveInfos == null || resolveInfos.isEmpty()) {
                return ToolResult.error("No installed apps found");
            }

            List<String> appList = new ArrayList<>();
            for (ResolveInfo info : resolveInfos) {
                String appName = info.loadLabel(pm).toString();
                String packageName = info.activityInfo.packageName;

                if (!keyword.isEmpty()) {
                    if (!appName.toLowerCase().contains(keyword.toLowerCase())
                            && !packageName.toLowerCase().contains(keyword.toLowerCase())) {
                        continue;
                    }
                }

                appList.add(appName + " | " + packageName);
            }

            if (appList.isEmpty()) {
                return ToolResult.success("No apps found matching keyword: " + keyword);
            }

            Collections.sort(appList);
            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(appList.size()).append(" apps:\n");
            for (String app : appList) {
                sb.append(app).append("\n");
            }
            return ToolResult.success(sb.toString());
        } catch (Exception e) {
            return ToolResult.error("Failed to get installed apps: " + e.getMessage());
        }
    }
}
