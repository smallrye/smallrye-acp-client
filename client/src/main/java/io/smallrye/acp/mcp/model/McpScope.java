package io.smallrye.acp.mcp.model;

import java.util.Arrays;
import java.util.Locale;

/**
 * Where an MCP server definition of the acp registry is stored.
 */
public enum McpScope {

    /** {@code ~/.acp/mcp.json}: available from any directory. */
    HOME("home"),
    /** {@code ./.acp/mcp.json} of the current directory: project specific, can be committed with the project. */
    LOCAL("local");

    private final String id;

    McpScope(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /**
     * Parses a scope name. Accepts {@code user} / {@code global} as aliases of {@link #HOME} and {@code project} as
     * an alias of {@link #LOCAL}.
     */
    public static McpScope from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Scope must not be empty");
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "home", "user", "global" -> HOME;
            case "local", "project" -> LOCAL;
            default -> throw new IllegalArgumentException("Unknown scope '" + value + "'. Supported: "
                    + Arrays.stream(values()).map(McpScope::id).toList());
        };
    }

    @Override
    public String toString() {
        return id;
    }
}
