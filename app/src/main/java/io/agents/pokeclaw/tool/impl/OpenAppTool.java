// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl;

import android.view.accessibility.AccessibilityNodeInfo;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;
import io.agents.pokeclaw.service.ClawAccessibilityService;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;
import io.agents.pokeclaw.utils.XLog;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class OpenAppTool extends BaseTool {

    private static final String TAG = "OpenAppTool";

    /**
     * 链式启动拦截弹窗上常见的"允许"按钮文案（覆盖主流厂商）
     * 小米/MIUI: "允许"
     * 华为/EMUI/HarmonyOS: "允许" / "允许打开"
     * OPPO/ColorOS: "允许" / "打开"
     * vivo/OriginOS: "允许"
     * 三星 OneUI: "允许"
     */
    private static final List<String> ALLOW_KEYWORDS = Arrays.asList(
            "允许", "允许打开", "打开", "Allow", "ALLOW"
    );

    @Override
    public String getName() {
        return "open_app";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_open_app);
    }

    @Override
    public String getDescriptionEN() {
        return "Open an application by its package name (e.g. 'com.android.settings').";
    }

    @Override
    public String getDescriptionCN() {
        return "通过包名打开应用（例如 'com.android.settings'）。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("package_name", "string", "The package name of the app to open", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = ClawAccessibilityService.getInstance();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        String packageName = requireString(params, "package_name");

        // If LLM sends app name instead of package name, resolve it
        if (!packageName.contains(".")) {
            String resolved = resolveAppName(packageName);
            if (resolved != null) {
                XLog.i(TAG, "Resolved app name '" + packageName + "' → '" + resolved + "'");
                packageName = resolved;
            }
        }

        boolean success = service.openApp(packageName);
        if (!success) {
            return ToolResult.error("Failed to open app: " + packageName + ". Make sure the app is installed.");
        }

        // 等待可能出现的链式启动拦截弹窗，并自动点击"允许"
        dismissChainLaunchDialog(service);

        return ToolResult.success("Opened app: " + packageName);
    }

    /**
     * 部分厂商（小米、华为、OPPO、vivo 等）在后台启动 App 时会弹出
     * "是否允许 xxx 打开 yyy"的拦截弹窗。
     * 此方法等待弹窗出现并自动点击"允许"按钮。
     * 最多检测 3 次，每次间隔 500ms，无弹窗时静默返回。
     */
    private void dismissChainLaunchDialog(ClawAccessibilityService service) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            for (String keyword : ALLOW_KEYWORDS) {
                List<AccessibilityNodeInfo> nodes = service.findNodesByText(keyword);
                try {
                    for (AccessibilityNodeInfo node : nodes) {
                        CharSequence text = node.getText();
                        if (text != null && matchesAllowButton(text.toString())) {
                            boolean clicked = service.clickNode(node);
                            XLog.i(TAG, "链式启动弹窗: 点击\"" + text + "\" " + (clicked ? "成功" : "失败"));
                            if (clicked) {
                                ClawAccessibilityService.recycleNodes(nodes);
                                return;
                            }
                        }
                    }
                } finally {
                    ClawAccessibilityService.recycleNodes(nodes);
                }
            }
        }
    }

    /**
     * Resolve common app names to package names.
     * Falls back to searching installed apps by label.
     */
    /** Public static version for other tools to reuse */
    public static String resolveAppNameStatic(String appName) {
        return new OpenAppTool().resolveAppName(appName);
    }

    private String resolveAppName(String appName) {
        String lower = appName.toLowerCase().trim();
        // Common app name → package name mapping
        switch (lower) {
            case "whatsapp": return "com.whatsapp";
            case "telegram": return "org.telegram.messenger";
            case "instagram": return "com.instagram.android";
            case "youtube": return "com.google.android.youtube";
            case "chrome": return "com.android.chrome";
            case "camera": return "com.android.camera2";
            case "settings": return "com.android.settings";
            case "messages": return "com.google.android.apps.messaging";
            case "gmail": return "com.google.android.gm";
            case "maps": return "com.google.android.apps.maps";
            case "phone": return "com.google.android.dialer";
            case "contacts": return "com.google.android.contacts";
            case "calendar": return "com.google.android.calendar";
            case "clock": return "com.google.android.deskclock";
            case "calculator": return "com.google.android.calculator";
            case "files": return "com.google.android.documentsui";
            case "photos": return "com.google.android.apps.photos";
            case "spotify": return "com.spotify.music";
            case "twitter": case "x": return "com.twitter.android";
            case "facebook": return "com.facebook.katana";
            case "tiktok": return "com.zhiliaoapp.musically";
            case "snapchat": return "com.snapchat.android";
            case "reddit": return "com.reddit.frontpage";
            case "discord": return "com.discord";
            case "slack": return "com.Slack";
            case "wechat": return "com.tencent.mm";
            case "line": return "jp.naver.line.android";
            default: break;
        }
        // Try to find by searching installed app labels
        try {
            android.content.pm.PackageManager pm = ClawApplication.Companion.getInstance().getPackageManager();
            for (android.content.pm.ApplicationInfo app : pm.getInstalledApplications(0)) {
                CharSequence label = pm.getApplicationLabel(app);
                if (label != null && label.toString().equalsIgnoreCase(appName)) {
                    return app.packageName;
                }
            }
        } catch (Exception e) {
            XLog.w(TAG, "resolveAppName: failed to search installed apps", e);
        }
        return null;
    }

    /**
     * 精确匹配允许按钮文案，避免误点内容中包含关键词的其他元素
     */
    private boolean matchesAllowButton(String text) {
        String trimmed = text.trim();
        for (String keyword : ALLOW_KEYWORDS) {
            if (trimmed.equals(keyword)) return true;
        }
        return false;
    }
}
