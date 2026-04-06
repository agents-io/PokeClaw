package io.agents.pokeclaw.tool.impl.tv;

import io.agents.pokeclaw.service.ClawAccessibilityService;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Base class for simple TV remote key tools that send a single key event.
 */
public abstract class BaseKeyTool extends BaseTool {

    /**
     * Returns the Android KeyEvent keycode to send.
     */
    protected abstract int getKeyCode();

    /**
     * Returns a human-readable label for logging (e.g. "D-pad Up").
     */
    protected abstract String getKeyLabel();

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.emptyList();
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = ClawAccessibilityService.getInstance();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        boolean success = service.sendKeyEvent(getKeyCode());
        return success
                ? ToolResult.success("Pressed " + getKeyLabel())
                : ToolResult.error("Failed to press " + getKeyLabel());
    }
}
