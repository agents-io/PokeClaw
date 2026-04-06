package io.agents.pokeclaw.tool.impl.mobile;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;
import io.agents.pokeclaw.service.ClawAccessibilityService;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class LongPressTool extends BaseTool {

    @Override
    public String getName() {
        return "long_press";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_long_press);
    }

    @Override
    public String getDescriptionEN() {
        return "Perform a long press at the specified screen coordinates (x, y) for a given duration.";
    }

    @Override
    public String getDescriptionCN() {
        return "在指定的屏幕坐标 (x, y) 处执行长按操作，持续指定时长。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("x", "integer", "X coordinate on screen", true),
                new ToolParameter("y", "integer", "Y coordinate on screen", true),
                new ToolParameter("duration_ms", "integer", "Duration of long press in milliseconds (default 1000)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = ClawAccessibilityService.getInstance();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        int x = requireInt(params, "x");
        int y = requireInt(params, "y");
        String boundsError = validateCoordinates(x, y);
        if (boundsError != null) return ToolResult.error(boundsError);
        long duration = optionalLong(params, "duration_ms", 1000);
        boolean success = service.performLongPress(x, y, duration);
        return success ? ToolResult.success("Long pressed at (" + x + ", " + y + ") for " + duration + "ms")
                : ToolResult.error("Failed to long press at (" + x + ", " + y + ")");
    }
}
