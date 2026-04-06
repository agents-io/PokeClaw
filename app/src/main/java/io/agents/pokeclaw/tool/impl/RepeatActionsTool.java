// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolRegistry;
import io.agents.pokeclaw.tool.ToolResult;
import io.agents.pokeclaw.utils.XLog;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 动作编排工具：将已有的原子工具按顺序组合成一个动作序列，并支持循环执行。
 *
 * AI 可以通过 actions 参数传入一组有序的工具调用，每个 action 指定要调用的工具名和参数，
 * 然后通过 repeat_count 控制整个序列重复执行的次数。
 *
 * 典型场景：
 * - 刷抖音：[swipe up, wait 10s] × 30 次
 * - 轮流滑动：[swipe up, wait, swipe down, wait, swipe left, wait, swipe right, wait] × 10 次
 * - 反复点赞：[tap 点赞位置, wait 5s, swipe up] × 20 次
 */
public class RepeatActionsTool extends BaseTool {

    private static final String TAG = "RepeatActionsTool";
    private static final Gson GSON = new Gson();
    private static final Random RANDOM = new Random();

    private static final int MAX_TOTAL_STEPS = 2000;
    private static final long MAX_TOTAL_DURATION_MS = 30 * 60 * 1000L; // 30 min

    private static final int DEFAULT_INTERVAL_MIN_MS = 3000;
    private static final int DEFAULT_INTERVAL_MAX_MS = 10000;

    private static final Set<String> BLOCKED_TOOLS = new HashSet<>(Arrays.asList(
            "repeat_actions", // prevent recursion
            "finish"          // should not be called inside a loop
    ));

    @Override
    public String getName() {
        return "repeat_actions";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_repeat_actions);
    }

    @Override
    public String getDescriptionEN() {
        return "Compose and repeat a sequence of existing tool actions. "
                + "Pass a JSON array of action steps in 'actions', each step is {\"tool\": \"<tool_name>\", \"params\": {<tool_params>}}. "
                + "The entire sequence will be executed 'repeat_count' times in order. "
                + "By default, a random delay of 3~10s is added between rounds to simulate human-like timing. "
                + "ONLY set interval_min_ms=interval_max_ms to a fixed value when the user EXPLICITLY requests a specific interval (e.g. 'every 10 seconds'). "
                + "Otherwise, always use the default random interval or customize the range via interval_min_ms/interval_max_ms. "
                + "Example: actions=[{\"tool\":\"swipe\",\"params\":{\"start_x\":540,\"start_y\":1600,\"end_x\":540,\"end_y\":400}}], repeat_count=30. "
                + "Cannot call 'repeat_actions' or 'finish' inside actions.";
    }

    @Override
    public String getDescriptionCN() {
        return "将已有的工具调用组合成动作序列并重复执行。"
                + "通过 actions 参数传入 JSON 数组，每个元素格式为 {\"tool\": \"工具名\", \"params\": {参数}}，"
                + "整个序列会按 repeat_count 次循环执行。"
                + "默认每轮之间自动添加 3~10 秒的随机间隔，模拟真人操作节奏。"
                + "仅当用户明确要求固定间隔时（如「每隔10秒刷一次」），才将 interval_min_ms 和 interval_max_ms 设为相同的固定值。"
                + "否则请使用默认的随机间隔，或自定义随机范围。"
                + "示例 - 刷抖音30次（默认随机间隔）: "
                + "actions=[{\"tool\":\"swipe\",\"params\":{\"start_x\":540,\"start_y\":1600,\"end_x\":540,\"end_y\":400}}], repeat_count=30。"
                + "actions 中不能调用 repeat_actions 和 finish。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("actions", "string",
                        "JSON array of action steps. Each step: {\"tool\": \"tool_name\", \"params\": {param_key: param_value}}. "
                                + "Example: [{\"tool\":\"swipe\",\"params\":{\"start_x\":540,\"start_y\":1600,\"end_x\":540,\"end_y\":400}}]",
                        true),
                new ToolParameter("repeat_count", "integer",
                        "Number of times to repeat the entire action sequence (default: 1)", false),
                new ToolParameter("interval_min_ms", "integer",
                        "Minimum random delay in ms between rounds (default: 3000). A random value in [min, max] is picked each round. Set min=max for fixed interval only when user explicitly requests it.", false),
                new ToolParameter("interval_max_ms", "integer",
                        "Maximum random delay in ms between rounds (default: 10000). A random value in [min, max] is picked each round. Set min=max for fixed interval only when user explicitly requests it.", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String actionsJson = requireString(params, "actions");
        int repeatCount = optionalInt(params, "repeat_count", 1);
        int intervalMinMs = optionalInt(params, "interval_min_ms", DEFAULT_INTERVAL_MIN_MS);
        int intervalMaxMs = optionalInt(params, "interval_max_ms", DEFAULT_INTERVAL_MAX_MS);

        // Parse actions
        List<ActionStep> actions;
        try {
            Type listType = new TypeToken<List<ActionStep>>() {}.getType();
            actions = GSON.fromJson(actionsJson, listType);
        } catch (Exception e) {
            return ToolResult.error("Failed to parse actions JSON: " + e.getMessage());
        }

        if (actions == null || actions.isEmpty()) {
            return ToolResult.error("actions array is empty or null");
        }

        // Validate
        if (repeatCount < 1) {
            return ToolResult.error("repeat_count must be at least 1");
        }

        if (intervalMinMs < 0 || intervalMaxMs < 0) {
            return ToolResult.error("interval_min_ms and interval_max_ms must be >= 0");
        }
        if (intervalMaxMs > 0 && intervalMinMs > intervalMaxMs) {
            return ToolResult.error("interval_min_ms must be <= interval_max_ms");
        }

        long totalSteps = (long) repeatCount * actions.size();
        if (totalSteps > MAX_TOTAL_STEPS) {
            return ToolResult.error("Total steps (" + totalSteps + ") exceeds max " + MAX_TOTAL_STEPS
                    + ". Reduce repeat_count or number of actions.");
        }

        // Validate each action
        for (int i = 0; i < actions.size(); i++) {
            ActionStep step = actions.get(i);
            if (step.tool == null || step.tool.isEmpty()) {
                return ToolResult.error("Action step " + i + " has no tool name");
            }
            if (BLOCKED_TOOLS.contains(step.tool)) {
                return ToolResult.error("Tool '" + step.tool + "' cannot be used inside repeat_actions");
            }
            if (ToolRegistry.getInstance().getTool(step.tool) == null) {
                return ToolResult.error("Unknown tool '" + step.tool + "' in action step " + i);
            }
            if (step.params == null) {
                step.params = Collections.emptyMap();
            }
        }

        // Estimate total wait time for duration check (include random interval estimate)
        long estimatedWaitMs = estimateTotalWait(actions, repeatCount);
        if (intervalMaxMs > 0 && repeatCount > 1) {
            long avgInterval = ((long) intervalMinMs + intervalMaxMs) / 2;
            estimatedWaitMs += avgInterval * (repeatCount - 1);
        }
        if (estimatedWaitMs > MAX_TOTAL_DURATION_MS) {
            return ToolResult.error("Estimated total wait time (" + estimatedWaitMs + "ms) exceeds max "
                    + MAX_TOTAL_DURATION_MS + "ms (30 min). Reduce wait durations or repeat_count.");
        }

        boolean hasRandomInterval = intervalMaxMs > 0;
        XLog.i(TAG, "Starting repeat_actions: " + actions.size() + " steps × " + repeatCount + " rounds"
                + (hasRandomInterval ? ", interval=" + intervalMinMs + "~" + intervalMaxMs + "ms" : ""));

        int totalSuccess = 0;
        int totalFail = 0;
        int completedRounds = 0;

        for (int round = 0; round < repeatCount; round++) {
            if (Thread.currentThread().isInterrupted()) {
                return buildResult(completedRounds, repeatCount, totalSuccess, totalFail, "interrupted");
            }

            // Random delay between rounds (skip before the first round)
            if (round > 0 && hasRandomInterval) {
                long sleepMs = randomInRange(intervalMinMs, intervalMaxMs);
                XLog.d(TAG, "Round " + (round + 1) + ": random interval " + sleepMs + "ms");
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return buildResult(completedRounds, repeatCount, totalSuccess, totalFail, "interrupted");
                }
            }

            for (int stepIdx = 0; stepIdx < actions.size(); stepIdx++) {
                if (Thread.currentThread().isInterrupted()) {
                    return buildResult(completedRounds, repeatCount, totalSuccess, totalFail, "interrupted");
                }

                ActionStep step = actions.get(stepIdx);
                ToolResult result = ToolRegistry.getInstance().executeTool(step.tool, step.params);

                if (result.isSuccess()) {
                    totalSuccess++;
                } else {
                    totalFail++;
                    XLog.w(TAG, "Step failed: round=" + (round + 1)
                            + ", step=" + stepIdx + " (" + step.tool + "): " + result.getError());
                }
            }

            completedRounds++;
        }

        return buildResult(completedRounds, repeatCount, totalSuccess, totalFail, "completed");
    }

    private long randomInRange(int minMs, int maxMs) {
        if (minMs >= maxMs) return minMs;
        return minMs + RANDOM.nextInt(maxMs - minMs + 1);
    }

    private long estimateTotalWait(List<ActionStep> actions, int repeatCount) {
        long waitPerRound = 0;
        for (ActionStep step : actions) {
            if ("wait".equals(step.tool) && step.params != null) {
                Object durationObj = step.params.get("duration_ms");
                if (durationObj instanceof Number) {
                    waitPerRound += ((Number) durationObj).longValue();
                }
            }
        }
        return waitPerRound * repeatCount;
    }

    private ToolResult buildResult(int completedRounds, int totalRounds, int success, int fail, String status) {
        StringBuilder sb = new StringBuilder();
        sb.append(status.equals("completed") ? "All rounds completed. " : "Stopped early (" + status + "). ");
        sb.append("Rounds: ").append(completedRounds).append("/").append(totalRounds);
        sb.append(", Actions: ").append(success).append(" succeeded");
        if (fail > 0) {
            sb.append(", ").append(fail).append(" failed");
        }
        sb.append(".");
        String msg = sb.toString();
        XLog.i(TAG, msg);
        return ToolResult.success(msg);
    }

    /**
     * Represents a single action step in the sequence.
     */
    static class ActionStep {
        String tool;
        Map<String, Object> params;
    }
}
