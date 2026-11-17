package io.smallrye.acp.mcp.probe;

import java.io.IOException;

/**
 * An HTTP MCP server answered a message with a non 2xx status.
 */
class McpHttpStatusException extends IOException {

    private final int status;

    McpHttpStatusException(int status, String message) {
        super(message);
        this.status = status;
    }

    int status() {
        return status;
    }
}
