package io.smallrye.acp.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import io.smallrye.acp.mcp.model.McpScope;
import io.smallrye.acp.mcp.model.McpServer;
import io.smallrye.acp.mcp.registry.McpRegistry;
import io.smallrye.acp.mcp.registry.McpServerJson;

/**
 * Adds the MCP servers declared in a JSON or TOML file, or in an inline JSON document.
 *
 * <p>
 * Accepted layouts: {@code {"mcpServers": {...}}} (acp, Claude, Gemini, IBM Bob, Pi), {@code {"mcp": {...}}}
 * (OpenCode), {@code [mcp_servers.<name>]} TOML tables (Codex) and the ACP entries accepted by
 * {@code acp run --mcp-server-config} (an object or an array of objects with a {@code name}).
 *
 * <pre>{@code
 * acp mcp add-config-file ./mcp-servers.json
 * acp mcp add-config-file ~/.codex/config.toml -s local
 * acp mcp add-config-file '{"type":"stdio","name":"quarkit","command":"jbang","args":["mcp@quarkit"]}'
 * }</pre>
 */
@CommandDefinition(name = "add-config-file", generateHelp = true, description = "Add the MCP servers of a JSON or TOML file, or of an inline JSON document")
public class McpAddConfigFileCommand implements Command<CommandInvocation> {

    @Option(shortName = 's', name = "scope", description = McpCommandSupport.SCOPE_HELP)
    String scope;

    @Option(shortName = 'f', name = "force", hasValue = false, description = "Replace the servers that already exist")
    boolean force;

    @Argument(required = true, description = "Path of a JSON/TOML file, or an inline JSON document")
    String source;

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        try {
            List<McpServer> servers = read(source);
            if (servers.isEmpty()) {
                invocation.println("No MCP server found in " + source);
                return CommandResult.FAILURE;
            }
            McpScope target = McpCommandSupport.scope(scope) != null ? McpCommandSupport.scope(scope) : McpScope.HOME;
            var registry = new McpRegistry();
            int added = 0;
            for (McpServer server : servers) {
                boolean existed = registry.find(target, server.name()).isPresent();
                if (existed && !force) {
                    invocation.println("Skipped  " + server.name() + " (already exists, use --force to replace it)");
                    continue;
                }
                registry.put(target, server);
                added++;
                invocation.println(String.format("%-8s %s (%s) %s", existed ? "Replaced" : "Added", server.name(),
                        server.transport(), McpCommandSupport.truncate(server.target(), 80)));
            }
            invocation.println(added + " MCP server(s) written to " + registry.file(target));
            return added > 0 ? CommandResult.SUCCESS : CommandResult.FAILURE;
        } catch (Exception e) {
            invocation.println("Error: " + e.getMessage());
            return CommandResult.FAILURE;
        }
    }

    static List<McpServer> read(String source) throws Exception {
        String trimmed = source.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return McpServerJson.parseConfig(trimmed, false);
        }
        String path = trimmed.startsWith("~/") ? System.getProperty("user.home") + trimmed.substring(1) : trimmed;
        Path file = Path.of(path);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("File not found: " + file);
        }
        return McpServerJson.parseConfig(Files.readString(file), file.toString().endsWith(".toml"));
    }
}
