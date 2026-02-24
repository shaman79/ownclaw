package com.ownclaw.mcp;

import java.util.ArrayList;
import java.util.List;

/** Configuration for a single MCP server (stdio transport). */
public class McpServer {

    private String name;
    /** Process command, e.g. ["node", "./server.js"] or ["python", "-m", "..."]. */
    private List<String> command = new ArrayList<>();
    /** Optional working directory for the MCP server process. */
    private String workingDir;

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
}
