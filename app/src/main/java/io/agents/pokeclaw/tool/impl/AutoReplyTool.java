// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl;

import io.agents.pokeclaw.service.AutoReplyManager;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * LLM-callable tool to enable or disable auto-reply for a contact.
 * The LLM decides when to call this and what parameters to pass,
 * so it works with any language or phrasing.
 */
public class AutoReplyTool extends BaseTool {

    @Override
    public String getName() {
        return "auto_reply";
    }

    @Override
    public String getDisplayName() {
        return "Auto Reply";
    }

    @Override
    public String getDescriptionEN() {
        return "Enable or disable automatic replies for a contact on messaging apps. " +
                "When enabled, the phone will monitor incoming messages from the specified contact " +
                "and automatically generate and send replies. Use action='on' with a contact name " +
                "to start, or action='off' to stop all auto-replies.";
    }

    @Override
    public String getDescriptionCN() {
        return "启用或禁用自动回复功能。启用后，手机会监控指定联系人的消息并自动生成回复。" +
                "action='on' 加联系人名字开启，action='off' 关闭。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("action", "string",
                        "Either 'on' to enable or 'off' to disable auto-reply", true),
                new ToolParameter("contact", "string",
                        "The contact name to monitor (required when action='on')", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String action = requireString(params, "action").toLowerCase().trim();
        AutoReplyManager manager = AutoReplyManager.getInstance();

        if (action.equals("off") || action.equals("disable") || action.equals("stop")) {
            manager.setEnabled(false);
            return ToolResult.success("Auto-reply disabled.");
        }

        if (action.equals("on") || action.equals("enable") || action.equals("start")) {
            String contact = params.containsKey("contact")
                    ? String.valueOf(params.get("contact")).trim()
                    : "";
            if (contact.isEmpty()) {
                return ToolResult.error("Contact name is required when enabling auto-reply.");
            }
            manager.addContact(contact);
            manager.setEnabled(true);
            return ToolResult.success("Auto-reply enabled for " + contact +
                    ". Monitoring incoming messages and will reply automatically.");
        }

        return ToolResult.error("Unknown action: " + action + ". Use 'on' or 'off'.");
    }
}
