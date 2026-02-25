package com.ownclaw.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal MCP client over stdio (JSON-RPC 2.0, newline-delimited JSON).
 *
 * This is intentionally small: start server process per request, initialize, perform a call, then exit.
 */
@Component
public class McpStdioClient {

    private static final Logger log = LoggerFactory.getLogger(McpStdioClient.class);

    private final ObjectMapper mapper;

    public McpStdioClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<McpTool> listTools(McpServer server, int timeoutSec) {
        try (Session session = start(server, timeoutSec)) {
            JsonNode result = session.request("tools/list", mapper.createObjectNode());
            JsonNode tools = result.path("tools");
            List<McpTool> out = new ArrayList<>();
            if (tools.isArray()) {
                for (JsonNode t : tools) {
                    out.add(new McpTool(
                            t.path("name").asText(""),
                            t.path("description").asText(""),
                            t.get("inputSchema")));
                }
            }
            return out;
        }
    }

    public JsonNode callTool(McpServer server, String toolName, JsonNode arguments, int timeoutSec) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Missing required param: tool");
        }
        try (Session session = start(server, timeoutSec)) {
            ObjectNode params = mapper.createObjectNode();
            params.put("name", toolName);
            if (arguments != null && !arguments.isNull()) {
                params.set("arguments", arguments);
            } else {
                params.set("arguments", mapper.createObjectNode());
            }
            return session.request("tools/call", params);
        }
    }

    private Session start(McpServer server, int timeoutSec) {
        if (server.getCommand() == null || server.getCommand().isEmpty()) {
            throw new IllegalStateException("MCP server command is empty for server=" + server.getName());
        }

        ProcessBuilder pb = new ProcessBuilder(server.getCommand());
        if (server.getWorkingDir() != null && !server.getWorkingDir().isBlank()) {
            pb.directory(Path.of(server.getWorkingDir()).toFile());
        }
        if (server.getEnv() != null && !server.getEnv().isEmpty()) {
            pb.environment().putAll(server.getEnv());
        }
        pb.redirectErrorStream(false);

        try {
            Process p = pb.start();
            return new Session(p, mapper, timeoutSec);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start MCP server '" + server.getName()
                    + "': " + e.getMessage(), e);
        }
    }

    private static class Session implements AutoCloseable {

        private final Process process;
        private final ObjectMapper mapper;
        private final BufferedReader stdout;
        private final BufferedWriter stdin;
        private final CompletableFuture<byte[]> stderrFuture;
        private final long deadlineMs;
        private int nextId = 1;

        Session(Process process, ObjectMapper mapper, int timeoutSec) {
            this.process = process;
            this.mapper = mapper;
            this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.stderrFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return process.getErrorStream().readAllBytes();
                } catch (Exception e) {
                    return new byte[0];
                }
            });
            this.deadlineMs = System.currentTimeMillis() + (timeoutSec * 1000L);

            initialize();
        }

        private void initialize() {
            ObjectNode params = mapper.createObjectNode();
            params.put("protocolVersion", "2024-11-05");
            params.set("capabilities", mapper.createObjectNode());
            params.set("clientInfo", mapper.convertValue(Map.of(
                    "name", "OwnClaw",
                    "version", "0.1"), JsonNode.class));

            // initialize
            request("initialize", params);

            // notifications/initialized (best-effort)
            try {
                ObjectNode notif = mapper.createObjectNode();
                notif.put("jsonrpc", "2.0");
                notif.put("method", "notifications/initialized");
                writeLine(notif);
            } catch (Exception ignored) {
            }
        }

        JsonNode request(String method, JsonNode params) {
            int id = nextId++;
            try {
                ObjectNode req = mapper.createObjectNode();
                req.put("jsonrpc", "2.0");
                req.put("id", id);
                req.put("method", method);
                if (params != null) {
                    req.set("params", params);
                }

                writeLine(req);

                while (true) {
                    if (System.currentTimeMillis() > deadlineMs) {
                        throw new IllegalStateException("MCP request timed out: " + method);
                    }

                    String line = stdout.readLine();
                    if (line == null) {
                        throw new IllegalStateException("MCP server closed stdout unexpectedly: " + method
                                + " | stderr=" + readStderr());
                    }

                    line = line.trim();
                    if (!line.startsWith("{")) continue;

                    JsonNode msg;
                    try {
                        msg = mapper.readTree(line);
                    } catch (Exception ignored) {
                        continue;
                    }

                    if (!msg.has("id")) {
                        // notification
                        continue;
                    }

                    if (msg.path("id").asInt(-1) != id) {
                        continue;
                    }

                    if (msg.has("error")) {
                        throw new IllegalStateException("MCP error for method=" + method + ": " + msg.get("error")
                                + " | stderr=" + readStderr());
                    }

                    return msg.path("result");
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("MCP I/O error for method=" + method + ": " + e.getMessage()
                        + " | stderr=" + readStderr(), e);
            }
        }

        private void writeLine(ObjectNode node) throws Exception {
            String json = mapper.writeValueAsString(node);
            stdin.write(json);
            stdin.write('\n');
            stdin.flush();
        }

        private String readStderr() {
            try {
                byte[] b = stderrFuture.getNow(new byte[0]);
                String s = new String(b, StandardCharsets.UTF_8);
                if (s.length() > 2000) s = s.substring(0, 2000) + "...";
                return s;
            } catch (Exception e) {
                return "";
            }
        }

        @Override
        public void close() {
            try {
                process.destroy();
            } catch (Exception ignored) {
            }
            try {
                process.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
    }
}
