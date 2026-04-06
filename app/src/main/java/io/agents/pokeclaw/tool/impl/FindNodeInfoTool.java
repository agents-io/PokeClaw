package io.agents.pokeclaw.tool.impl;

import android.view.accessibility.AccessibilityNodeInfo;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;
import io.agents.pokeclaw.service.ClawAccessibilityService;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class FindNodeInfoTool extends BaseTool {

    @Override
    public String getName() {
        return "find_node_info";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_find_node_info);
    }

    @Override
    public String getDescriptionEN() {
        return "Find elements by visible text and return their detailed information (class, bounds, properties). Useful for inspecting specific elements before interacting.";
    }

    @Override
    public String getDescriptionCN() {
        return "通过可见文本查找元素，返回详细信息（类名、边界、属性）。适用于在交互前检查特定元素。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("text", "string", "The visible text to search for", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = ClawAccessibilityService.getInstance();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }

        String text = requireString(params, "text");
        List<AccessibilityNodeInfo> nodes = service.findNodesByText(text);

        if (nodes.isEmpty()) {
            return ToolResult.error("No elements found with text: " + text);
        }

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(nodes.size()).append(" element(s):\n");
            for (int i = 0; i < nodes.size(); i++) {
                sb.append("[").append(i).append("] ").append(service.getNodeDetail(nodes.get(i))).append("\n");
            }
            return ToolResult.success(sb.toString());
        } finally {
            ClawAccessibilityService.recycleNodes(nodes);
        }
    }
}
