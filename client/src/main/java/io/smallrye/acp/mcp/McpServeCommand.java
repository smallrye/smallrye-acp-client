package io.smallrye.acp.mcp;

import java.util.ArrayList;
import java.util.List;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import io.smallrye.acp.mcp.model.McpServer;
import io.smallrye.acp.mcp.model.McpTransport;
import io.smallrye.acp.mcp.registry.AcpMcpServers;
import io.smallrye.acp.mcp.registry.McpEnv;
import io.smallrye.acp.mcp.registry.McpRegistry;

/**
 * Starts, in the foreground, the process serving a registered HTTP MCP server (its {@code serve} command), so that
 * agents can reach it with {@code acp run --mcp-server <id>}. Stop it with Ctrl-C.
 *
 * <pre>{@code
 * acp mcp serve quarkit-http
 * }</pre>
 */
@CommandDefinition(name = "serve", generateHelp = true, description = "Start (in the foreground) the process serving an HTTP MCP server")
public class McpServeCommand implements Command<CommandInvocation> {

    @Option(name = "ready-timeout", description = "Seconds to wait for the url to accept connections before warning (default: 120)")
    Integer readyTimeout;

    @Argument(required = true, description = "Id of the http streamable MCP server to launch")
    String id;

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        try {
            var registry = new McpRegistry();
            McpServer server = registry.require(id);
            if (server.transport() == McpTransport.STDIO) {
                invocation.println("'" + id + "' is a stdio MCP server: the agent starts it itself, nothing to serve.");
                invocation.println("Use it with: acp run -a <agent> --mcp-server " + id + " -p \"...\"");
                return CommandResult.FAILURE;
            }
            if (server.serve() == null) {
                invocation.println("'" + id + "' has no serve command: " + server.url() + " is expected to be served "
                        + "elsewhere.");
                invocation.println("Define one with: acp mcp add -f -t " + server.transport() + " " + id + " "
                        + server.url() + " -- <command> [args...]");
                return CommandResult.FAILURE;
            }
            McpServer expanded = McpEnv.expand(server);
            if (AcpMcpServers.isReachable(expanded.url(), 1000)) {
                invocation.println("Warning: " + expanded.url() + " already accepts connections: another process may "
                        + "already serve it.");
            }

            List<String> command = new ArrayList<>();
            command.add(expanded.serve().command());
            command.addAll(expanded.serve().args());
            ProcessBuilder pb = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT);
            pb.environment().putAll(expanded.serve().env());

            invocation.println("Serving MCP server '" + id + "': " + String.join(" ", command));
            Process process = pb.start();
            // stdin is kept open but never written: a server with stdio enabled must not see EOF and stop
            Thread hook = new Thread(() -> stop(process));
            Runtime.getRuntime().addShutdownHook(hook);

            waitUntilReady(invocation, process, expanded, readyTimeout == null ? 120 : readyTimeout);
            int exit = process.waitFor();
            Runtime.getRuntime().removeShutdownHook(hook);
            invocation.println("MCP server '" + id + "' stopped (exit code " + exit + ")");
            return exit == 0 ? CommandResult.SUCCESS : CommandResult.FAILURE;
        } catch (Exception e) {
            invocation.println("Error: " + e.getMessage());
            return CommandResult.FAILURE;
        }
    }

    private static void waitUntilReady(CommandInvocation invocation, Process process, McpServer server, int seconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (process.isAlive() && System.nanoTime() < deadline) {
            if (AcpMcpServers.isReachable(server.url(), 500)) {
                invocation.println("");
                invocation.println("MCP server '" + server.name() + "' is ready at " + server.url()
                        + " (" + server.transport() + ") - press Ctrl-C to stop it");
                invocation.println("Use it with: acp run -a <agent> --mcp-server " + server.name() + " -p \"...\"");
                return;
            }
            Thread.sleep(500);
        }
        if (process.isAlive()) {
            invocation.println("Warning: " + server.url() + " does not accept connections after " + seconds + "s");
        }
    }

    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
