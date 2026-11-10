package io.smallrye.acp.mcp.probe;

import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A JSON-RPC channel to an MCP server.
 */
interface McpConnection extends AutoCloseable {

    /** Sends a request and waits for the response carrying the same id. */
    JsonNode request(ObjectNode request, Duration timeout) throws Exception;

    /** Sends a notification (no response expected). */
    void notify(ObjectNode notification) throws Exception;

    @Override
    void close();
}
