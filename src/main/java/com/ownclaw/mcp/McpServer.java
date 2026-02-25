package com.ownclaw.mcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Configuration for a single MCP server (stdio transport). */
public class McpServer {

    private String name;
    /** Process command, e.g. ["node", "./server.js"] or ["python", "-m", "..."] */
    private List<String> command = new ArrayList<>();
    /** Optional working directory for the MCP server process. */
    private String workingDir;
    /** Optional extra environment variables to inject into the server process. */
    private Map<String, String> env = new HashMap<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getCommand() {
        return command;
    }

    public void setCommand(List<String> command) {
        this.command = command;
    }

    public String getWorkingDir() {
        return workingDir;
    }

    public void setWorkingDir(String workingDir) {
        this.workingDir = workingDir;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env;
    }
}
