package com.ownclaw.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.core.StepResult;
import com.ownclaw.core.TaskStep;
import com.ownclaw.skillrunner.NativeSkill;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class McpListToolsSkill implements NativeSkill {

    private final McpBridgeService bridge;
    private final ObjectMapper mapper;

    public McpListToolsSkill(McpBridgeService bridge, ObjectMapper mapper) {
        this.bridge = bridge;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "mcp_list_tools";
    }

    @Override
    public StepResult execute(TaskStep step, String userId, String taskId, Map<String, Object> resolvedParams) {
        long start = System.currentTimeMillis();
        try {
            String server = resolvedParams != null && resolvedParams.get("server") != null
                    ? resolvedParams.get("server").toString()
                    : null;
            List<McpTool> tools = bridge.listTools(server);
            String json = mapper.writeValueAsString(tools);
            long duration = System.currentTimeMillis() - start;
            return StepResult.successWithContext(step.id(), json, duration, name(), resolvedParams);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            String msg = e.getMessage() != null ? e.getMessage() : "MCP listTools failed";
            return StepResult.failureWithContext(step.id(), msg, -1, duration, name(), resolvedParams);
        }
    }
}
