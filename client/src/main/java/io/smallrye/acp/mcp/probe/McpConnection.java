package io.smallrye.acp.mcp.probe;

import java.nio.file.Path;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.smallrye.acp.mcp.model.McpServer;

/**
 * A JSON-RPC channel to an MCP server.
 */
interface McpConnection extends AutoCloseable {

    /** Sends a request and waits for the response carrying the same id. */
    JsonNode request(ObjectNode request, Duration timeout) throws Exception;

    /** Sends a notification (no response expected). */
    void notify(ObjectNode notification) throws Exception;

    /**
     * Opens a connection to a server whose variables are already expanded: starts the process of a stdio server,
     * connects to the url of an http/sse one.
     */
    static McpConnection open(McpServer server, Duration timeout, Path workDir) throws Exception {
        return switch (server.transport()) {
            case STDIO -> new StdioMcpConnection(server, workDir);
            case HTTP -> new HttpMcpConnection(server, timeout);
            case SSE -> new SseMcpConnection(server, timeout);
        };
    }
}
