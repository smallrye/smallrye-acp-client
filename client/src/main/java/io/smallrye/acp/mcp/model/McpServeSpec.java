package io.smallrye.acp.mcp.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How to start locally the process serving an HTTP MCP server ({@code acp mcp serve <id>}).
 *
 * @param command executable
 * @param args arguments (never {@code null})
 * @param env extra environment variables (never {@code null})
 */
public record McpServeSpec(String command, List<String> args, Map<String, String> env) {

    public McpServeSpec {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("The serve command must not be empty");
        }
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(env));
    }

    public String commandLine() {
        return args.isEmpty() ? command : command + " " + String.join(" ", args);
    }
}
