package io.agents.pokeclaw.tool.impl.mobile;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import io.agents.pokeclaw.utils.XLog;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;
import io.agents.pokeclaw.service.ClawAccessibilityService;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Opens the device's app store by brand detection.
 * The agent should then use UI tools (get_screen_info, tap, input_text, etc.)
 * to find the search box and type the keyword.
 */
public class SearchAppInStoreTool extends BaseTool {

    private static final String TAG = "SearchAppInStoreTool";

    private static final String[][] BRAND_STORES = {
            // { brand keyword, store package name, store display name }
            {"huawei",   "com.huawei.appmarket",              "Huawei AppGallery"},
            {"honor",    "com.huawei.appmarket",              "Huawei AppGallery"},
            {"xiaomi",   "com.xiaomi.market",                 "Xiaomi App Store"},
            {"redmi",    "com.xiaomi.market",                 "Xiaomi App Store"},
            {"poco",     "com.xiaomi.market",                 "Xiaomi App Store"},
            {"oppo",     "com.heytap.market",                   "OPPO App Store"},
            {"oneplus",  "com.heytap.market",                   "OPPO App Store"},
            {"realme",   "com.heytap.market",                   "OPPO App Store"},
            {"vivo",     "com.bbk.appstore",                  "Vivo App Store"},
            {"iqoo",     "com.bbk.appstore",                  "Vivo App Store"},
            {"samsung",  "com.sec.android.app.samsungapps",   "Samsung Galaxy Store"},
            {"meizu",    "com.meizu.mstore",                  "Meizu App Store"},
            {"lenovo",   "com.lenovo.leos.appstore",          "Lenovo App Store"},
            {"motorola", "com.lenovo.leos.appstore",          "Lenovo App Store"},
    };

    private static final String[][] FALLBACK_STORES = {
            {"com.tencent.android.qqdownloader", "Tencent MyApp"},
            {"com.google.android.apps.market",   "Google Play Store"},
    };

    @Override
    public String getName() {
        return "search_app_in_store";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_search_app);
    }

    @Override
    public String getDescriptionEN() {
        return "Open the phone's app store so you can search and download an app. "
                + "Automatically detects the phone brand and opens the corresponding app store. "
                + "After this tool opens the store, you should use get_screen_info to see the store UI, "
                + "then find the search input and use input_text or tap to type the keyword and search. "
                + "Use this when open_app fails because the app is not installed.";
    }

    @Override
    public String getDescriptionCN() {
        return "打开手机应用商店以便搜索和下载应用。自动检测手机品牌并打开对应的应用商店。"
                + "此工具打开商店后，你需要使用 get_screen_info 查看商店界面，"
                + "然后找到搜索框并使用 input_text 或 tap 输入关键词进行搜索。"
                + "当 open_app 因应用未安装而失败时使用此工具。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("keyword", "string",
                        "The app name to search for (returned in result for your reference, "
                                + "you need to type it into the store's search box afterwards)", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = ClawAccessibilityService.getInstance();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }

        String keyword = requireString(params, "keyword");
        Context context = ClawApplication.Companion.getInstance();

        String brand = Build.MANUFACTURER.toLowerCase().trim();
        XLog.i(TAG, "Device brand: " + brand);

        // 1. Try brand-specific store
        for (String[] entry : BRAND_STORES) {
            if (brand.contains(entry[0])) {
                String pkgName = entry[1];
                String storeName = entry[2];
                try {
                    launchApp(context, pkgName);
                    return ToolResult.success("Opened " + storeName + " (" + pkgName + "). "
                            + "Brand: " + brand + ". "
                            + "Now use get_screen_info to find the search box, "
                            + "then type \"" + keyword + "\" to search.");
                } catch (Exception e) {
                    XLog.w(TAG, "Brand store " + pkgName + " launch failed, trying fallback", e);
                    break;
                }
            }
        }

        // 2. Try fallback stores
        for (String[] fallback : FALLBACK_STORES) {
            String pkgName = fallback[0];
            String storeName = fallback[1];
            try {
                launchApp(context, pkgName);
                return ToolResult.success("Opened " + storeName + " (" + pkgName + "). "
                        + "Now use get_screen_info to find the search box, "
                        + "then type \"" + keyword + "\" to search.");
            } catch (Exception e) {
                XLog.w(TAG, "Fallback store " + pkgName + " launch failed", e);
            }
        }

        return ToolResult.error("No app store found on this device. Brand: " + brand
                + ". You may need to guide the user to install the app manually.");
    }

    private void launchApp(Context context, String packageName) throws Exception {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            throw new Exception("Cannot resolve launch intent for " + packageName);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
