package com.ownclaw.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.ownclaw.core.StepResult;
import com.ownclaw.core.TaskStep;
import com.ownclaw.skillrunner.NativeSkill;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class McpCallToolSkill implements NativeSkill {

    private final McpBridgeService bridge;
    private final ObjectMapper mapper;

    public McpCallToolSkill(McpBridgeService bridge, ObjectMapper mapper) {
        this.bridge = bridge;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "mcp_call_tool";
    }

    @Override
    public StepResult execute(TaskStep step, String userId, String taskId, Map<String, Object> resolvedParams) {
        long start = System.currentTimeMillis();
        try {
            String server = resolvedParams != null && resolvedParams.get("server") != null
                    ? resolvedParams.get("server").toString()
                    : null;

            Object toolObj = resolvedParams != null ? resolvedParams.get("tool") : null;
            String tool = toolObj != null ? toolObj.toString() : null;
            if (tool == null || tool.isBlank()) {
                long duration = System.currentTimeMillis() - start;
                return StepResult.failureWithContext(step.id(), "Missing required param: tool", -1,
                        duration, name(), resolvedParams);
            }

            JsonNode args = NullNode.getInstance();
            Object argObj = resolvedParams != null ? resolvedParams.get("arguments") : null;
            if (argObj instanceof JsonNode jn) {
                args = jn;
            } else if (argObj instanceof Map<?, ?> map) {
                args = mapper.valueToTree(map);
            } else if (argObj != null) {
                // If caller provided a JSON string, attempt to parse it.
                try {
                    args = mapper.readTree(argObj.toString());
                } catch (Exception ignored) {
                    // fall back to string value
                    args = mapper.valueToTree(argObj.toString());
                }
            }

            JsonNode result = bridge.callTool(server, tool, args);
            String json = mapper.writeValueAsString(result);
            long duration = System.currentTimeMillis() - start;
            return StepResult.successWithContext(step.id(), json, duration, name(), resolvedParams);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            String msg = e.getMessage() != null ? e.getMessage() : "MCP callTool failed";
            return StepResult.failureWithContext(step.id(), msg, -1, duration, name(), resolvedParams);
        }
    }
}
