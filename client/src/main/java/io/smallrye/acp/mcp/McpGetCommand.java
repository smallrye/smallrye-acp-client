package io.smallrye.acp.mcp;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import io.smallrye.acp.mcp.model.McpServer;
import io.smallrye.acp.mcp.model.McpTransport;
import io.smallrye.acp.mcp.probe.McpProbe;
import io.smallrye.acp.mcp.registry.McpRegistry;

/**
 * Shows the definition of a registered MCP server and, with {@code --check}, the tools it exposes.
 *
 * <pre>{@code
 * acp mcp get quarkit
 * acp mcp get quarkit --check              # initialize + tools/list
 * acp mcp get quarkit --check --verbose    # ... and print the JSON-RPC requests/responses
 * }</pre>
 */
@CommandDefinition(name = "get", generateHelp = true, description = "Get details about an MCP server")
public class McpGetCommand implements Command<CommandInvocation> {

    @Option(shortName = 's', name = "scope", description = "Look only in this scope: home or local (the effective definition when omitted)")
    String scope;

    @Option(shortName = 'c', name = "check", hasValue = false, description = "Connect to the server (starting it for stdio), call initialize and tools/list, and print its tools")
    boolean check;

    @Option(shortName = 'v', name = "verbose", hasValue = false, description = "With --check: print the JSON-RPC requests/responses exchanged with the server")
    boolean verbose;

    @Option(name = "timeout", description = McpCommandSupport.TIMEOUT_HELP)
    Integer timeout;

    @Argument(required = true, description = "Id of the MCP server")
    String id;

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        try {
            var registry = new McpRegistry();
            var requested = McpCommandSupport.scope(scope);
            var found = requested != null ? registry.find(requested, id) : registry.find(id);
            if (found.isEmpty()) {
                invocation.println(registry.unknownMessage(id));
                return CommandResult.FAILURE;
            }
            McpRegistry.Entry entry = found.get();
            invocation.println(id + ":");
            invocation.println("  Scope:     " + entry.scope() + " (" + entry.file() + ")");
            if (entry.server() == null) {
                invocation.println("  Invalid:   " + entry.error());
                return CommandResult.FAILURE;
            }
            McpServer server = entry.server();
            invocation.println("  Transport: " + server.transport()
                    + (server.transport() == McpTransport.STDIO ? " (started by the agent)"
                            : " (the agent connects to the url)"));
            if (server.transport() == McpTransport.STDIO) {
                invocation.println("  Command:   " + server.command());
                invocation.println("  Args:      " + String.join(" ", server.args()));
                server.env().forEach((k, v) -> invocation.println("  Env:       " + k + "=" + v));
            } else {
                invocation.println("  URL:       " + server.url());
                server.headers().forEach((k, v) -> invocation.println("  Header:    " + k + ": " + v));
                if (server.serve() != null) {
                    invocation.println("  Serve:     " + server.serve().commandLine()
                            + "   (acp mcp serve " + id + ")");
                    server.serve().env().forEach((k, v) -> invocation.println("  Serve env: " + k + "=" + v));
                }
            }
            if (!check) {
                printCommands(invocation);
                return CommandResult.SUCCESS;
            }
            invocation.println("");
            McpProbe.Listener listener = verbose ? McpCommandSupport.traceListener(invocation) : McpProbe.Listener.NONE;
            McpProbe.Result result = McpCommandSupport.probe(server, McpCommandSupport.timeout(timeout), listener);
            if (verbose) {
                invocation.println("");
            }
            if (!result.up()) {
                invocation.println("  Status:    down - " + result.error());
                if (server.transport() != McpTransport.STDIO && server.serve() != null) {
                    invocation.println("  Start it first with: acp mcp serve " + id);
                }
                printCommands(invocation);
                return CommandResult.FAILURE;
            }
            invocation.println(String.format("  Status:    up (%d ms) - %s %s - protocol %s", result.elapsed().toMillis(),
                    result.serverName(), result.serverVersion() != null ? result.serverVersion() : "",
                    result.protocolVersion()));
            invocation.println("  Tools (" + result.tools().size() + "):");
            int width = result.tools().stream().mapToInt(t -> t.name().length()).max().orElse(10) + 2;
            for (McpProbe.Tool tool : result.tools()) {
                invocation.println(String.format("    %-" + width + "s %s", tool.name(),
                        McpCommandSupport.truncate(McpCommandSupport.firstLine(tool.description()), 100)));
            }
            printCommands(invocation);
            return CommandResult.SUCCESS;
        } catch (Exception e) {
            invocation.println("Error: " + e.getMessage());
            return CommandResult.FAILURE;
        }
    }

    /** Printed last: how to use the server with an agent. */
    private void printCommands(CommandInvocation invocation) {
        invocation.println("");
        invocation.println("  Commands:");
        invocation.println("    To use it:  acp run -a <agent> --mcp-server " + id + " -p \"...\"");
    }
}
