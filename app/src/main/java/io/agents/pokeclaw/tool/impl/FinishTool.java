// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class FinishTool extends BaseTool {

    @Override
    public String getName() {
        return "finish";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_finish);
    }

    @Override
    public String getDescriptionEN() {
        return "Signal that the current task is complete. Call this when you have successfully accomplished the user's request. Provide a summary of what was done.";
    }

    @Override
    public String getDescriptionCN() {
        return "标记当前任务已完成。当成功完成用户请求时调用此工具。提供已完成工作的摘要。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("summary", "string", "A brief summary of what was accomplished", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String summary = requireString(params, "summary");
        return ToolResult.success("完成任务: " + summary);
    }
}
