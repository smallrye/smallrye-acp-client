package io.smallrye.acp.mcp.model;

import java.util.Arrays;
import java.util.Locale;

/**
 * Transport used to reach an MCP server, independent of how a given agent spells it in its config file.
 */
public enum McpTransport {

    /** Local process speaking MCP over stdin/stdout. */
    STDIO("stdio"),
    /** Remote server using the Streamable HTTP transport. */
    HTTP("http"),
    /** Remote server using the (deprecated) HTTP + Server-Sent Events transport. */
    SSE("sse");

    private final String id;

    McpTransport(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean isRemote() {
        return this != STDIO;
    }

    /**
     * Parses a transport name. Accepts {@code streamable-http} / {@code streamableHttp} as aliases of {@link #HTTP}.
     */
    public static McpTransport from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Transport must not be empty");
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "stdio", "local" -> STDIO;
            case "http", "streamable-http", "streamablehttp", "remote" -> HTTP;
            case "sse" -> SSE;
            default -> throw new IllegalArgumentException("Unknown transport '" + value + "'. Supported: "
                    + Arrays.stream(values()).map(McpTransport::id).toList());
        };
    }

    @Override
    public String toString() {
        return id;
    }
}
