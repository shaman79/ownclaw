package com.ownclaw.agent.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.mcp.McpBridgeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent tool that invokes a tool on a configured MCP server.
 * Only registered when MCP is enabled (ownclaw.mcp.enabled=true).
 */
@Component
@ConditionalOnProperty(name = "ownclaw.mcp.enabled", havingValue = "true")
public class McpCallTool implements Tool {

    private final McpBridgeService bridge;
    private final ObjectMapper mapper;

    public McpCallTool(McpBridgeService bridge, ObjectMapper mapper) {
        this.bridge = bridge;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "mcp_call_tool";
    }

    @Override
    public String description() {
        return "Invoke a tool on a connected MCP (Model Context Protocol) server. " +
                "Use mcp_list_tools first to discover available tools and their parameters.";
    }

    @Override
    public Map<String, ToolParam> inputSchema() {
        var schema = new LinkedHashMap<String, ToolParam>();
        schema.put("tool", ToolParam.required("string", "The name of the MCP tool to invoke"));
        schema.put("arguments", ToolParam.optional("object", "JSON arguments to pass to the tool (structure depends on the tool)"));
        schema.put("server", ToolParam.optional("string", "Name of the MCP server (uses default if omitted)"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolExecutionContext ctx) {
        String toolName = params.get("tool") != null ? params.get("tool").toString() : null;
        if (toolName == null || toolName.isBlank()) {
            return ToolResult.failure("Missing required parameter: tool");
        }

        String server = params.get("server") != null ? params.get("server").toString() : null;

        try {
            JsonNode args = resolveArguments(params.get("arguments"));
            JsonNode result = bridge.callTool(server, toolName, args);
            String json = mapper.writeValueAsString(result);
            return ToolResult.success(json);
        } catch (Exception e) {
            return ToolResult.failure(e.getMessage() != null ? e.getMessage() : "MCP tool call failed");
        }
    }

    private JsonNode resolveArguments(Object argObj) {
        if (argObj == null) return mapper.createObjectNode();
        if (argObj instanceof JsonNode jn) return jn;
        if (argObj instanceof Map<?, ?>) return mapper.valueToTree(argObj);
        try {
            return mapper.readTree(argObj.toString());
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    @Override
    public boolean requiresNetwork() {
        return false; // MCP servers are local processes (stdio transport)
    }

    @Override
    public boolean hasSideEffects() {
        return true; // MCP tools can have arbitrary side effects
    }
}
