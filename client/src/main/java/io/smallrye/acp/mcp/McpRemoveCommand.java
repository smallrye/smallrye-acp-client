package io.smallrye.acp.mcp;

import java.util.ArrayList;
import java.util.List;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import io.smallrye.acp.mcp.model.McpScope;
import io.smallrye.acp.mcp.registry.McpRegistry;

/**
 * Removes a registered MCP server. Without {@code --scope}, the server is removed when it is defined in exactly one
 * scope.
 *
 * <pre>{@code
 * acp mcp remove quarkit
 * acp mcp remove -s local quarkit
 * }</pre>
 */
@CommandDefinition(name = "remove", generateHelp = true, description = "Remove an MCP server")
public class McpRemoveCommand implements Command<CommandInvocation> {

    @Option(shortName = 's', name = "scope", description = "Scope to remove the server from: home or local (searched in both when omitted)")
    String scope;

    @Argument(required = true, description = "Id of the MCP server to remove")
    String id;

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        try {
            var registry = new McpRegistry();
            McpScope requested = McpCommandSupport.scope(scope);
            List<McpScope> matches = new ArrayList<>();
            for (McpScope s : requested != null ? List.of(requested) : List.of(McpScope.LOCAL, McpScope.HOME)) {
                if (registry.find(s, id).isPresent()) {
                    matches.add(s);
                }
            }
            if (matches.isEmpty()) {
                invocation.println(registry.unknownMessage(id));
                return CommandResult.FAILURE;
            }
            if (matches.size() > 1) {
                invocation.println("MCP server '" + id + "' is defined in both scopes, use --scope home|local:");
                matches.forEach(s -> invocation.println("  " + s + ": " + registry.file(s)));
                return CommandResult.FAILURE;
            }
            registry.remove(matches.get(0), id);
            invocation.println("Removed MCP server '" + id + "' from " + registry.file(matches.get(0)));
            return CommandResult.SUCCESS;
        } catch (Exception e) {
            invocation.println("Error: " + e.getMessage());
            return CommandResult.FAILURE;
        }
    }
}
