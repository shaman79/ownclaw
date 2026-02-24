package com.ownclaw.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.ownclaw.config.OwnClawConfig;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class McpBridgeService {

    private final OwnClawConfig.Mcp mcpConfig;
    private final McpStdioClient client;

    public McpBridgeService(OwnClawConfig config, McpStdioClient client) {
        this.mcpConfig = config.getMcp();
        this.client = client;
    }

    public List<McpTool> listTools(String serverName) {
        McpServer server = resolveServer(serverName);
        return client.listTools(server, mcpConfig.getTimeoutSec());
    }

    public JsonNode callTool(String serverName, String toolName, JsonNode arguments) {
        McpServer server = resolveServer(serverName);
        return client.callTool(server, toolName, arguments, mcpConfig.getTimeoutSec());
    }

    private McpServer resolveServer(String serverName) {
        if (!mcpConfig.isEnabled()) {
            throw new IllegalStateException("MCP is disabled (ownclaw.mcp.enabled=false)");
        }

        List<McpServer> servers = mcpConfig.getServers();
        if (servers == null || servers.isEmpty()) {
            throw new IllegalStateException("No MCP servers configured (ownclaw.mcp.servers)");
        }

        if (serverName == null || serverName.isBlank()) {
            return servers.getFirst();
        }

        Optional<McpServer> found = servers.stream()
                .filter(s -> serverName.equalsIgnoreCase(s.getName()))
                .findFirst();

        return found.orElseThrow(() -> new IllegalArgumentException(
                "Unknown MCP server: " + serverName));
    }
}
