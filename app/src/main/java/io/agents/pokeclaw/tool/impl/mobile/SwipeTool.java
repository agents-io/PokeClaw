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

public class SwipeTool extends BaseTool {

    @Override
    public String getName() {
        return "swipe";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_swipe);
    }

    @Override
    public String getDescriptionEN() {
        return "Swipe from one point to another on the screen. Useful for scrolling, pulling down notifications, etc.";
    }

    @Override
    public String getDescriptionCN() {
        return "在屏幕上从一个点滑动到另一个点。适用于滚动、下拉通知等操作。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("start_x", "integer", "Start X coordinate", true),
                new ToolParameter("start_y", "integer", "Start Y coordinate", true),
                new ToolParameter("end_x", "integer", "End X coordinate", true),
                new ToolParameter("end_y", "integer", "End Y coordinate", true),
                new ToolParameter("duration_ms", "integer", "Swipe duration in milliseconds (default 500)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = ClawAccessibilityService.getInstance();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        int startX = requireInt(params, "start_x");
        int startY = requireInt(params, "start_y");
        int endX = requireInt(params, "end_x");
        int endY = requireInt(params, "end_y");
        String boundsError = validateCoordinates(startX, startY);
        if (boundsError != null) return ToolResult.error(boundsError);
        boundsError = validateCoordinates(endX, endY);
        if (boundsError != null) return ToolResult.error(boundsError);
        long duration = optionalLong(params, "duration_ms", 500);
        boolean success = service.performSwipe(startX, startY, endX, endY, duration);
        return success ? ToolResult.success("Swiped from (" + startX + ", " + startY + ") to (" + endX + ", " + endY + ")")
                : ToolResult.error("Failed to swipe");
    }
}
