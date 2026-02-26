package com.ownclaw.agent.tools.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.mcp.McpBridgeService;
import com.ownclaw.mcp.McpTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent tool that discovers tools available on a configured MCP server.
 * Only registered when MCP is enabled (ownclaw.mcp.enabled=true).
 */
@Component
@ConditionalOnProperty(name = "ownclaw.mcp.enabled", havingValue = "true")
public class McpListToolsTool implements Tool {

    private final McpBridgeService bridge;
    private final ObjectMapper mapper;

    public McpListToolsTool(McpBridgeService bridge, ObjectMapper mapper) {
        this.bridge = bridge;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "mcp_list_tools";
    }

    @Override
    public String description() {
        return "List all tools available on a connected MCP (Model Context Protocol) server. " +
                "Returns tool names, descriptions, and their input schemas.";
    }

    @Override
    public Map<String, ToolParam> inputSchema() {
        var schema = new LinkedHashMap<String, ToolParam>();
        schema.put("server", ToolParam.optional("string", "Name of the MCP server (uses default if omitted)"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolExecutionContext ctx) {
        String server = params.get("server") != null ? params.get("server").toString() : null;

        try {
            List<McpTool> tools = bridge.listTools(server);
            String json = mapper.writeValueAsString(tools);
            return ToolResult.success(json);
        } catch (Exception e) {
            return ToolResult.failure(e.getMessage() != null ? e.getMessage() : "MCP list tools failed");
        }
    }

    @Override
    public boolean requiresNetwork() {
        return false; // MCP servers are local processes
    }
}
