package io.smallrye.acp.mcp;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;

import io.smallrye.acp.mcp.model.McpServeSpec;
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
 *
 * # Pass parameters to the server as environment variables (added to, or overriding, the ones of the registry).
 * # Quarkus maps QUARKIT_ALLOWED_DIRS to the property quarkit.allowed-dirs
 * acp mcp serve -e QUARKIT_ALLOWED_DIRS=./applications,/tmp/projects -e QUARKIT_REFERENCES_PATH=./refs quarkit-http
 * }</pre>
 */
@CommandDefinition(name = "serve", generateHelp = true, description = "Start (in the foreground) the process serving an HTTP MCP server")
public class McpServeCommand implements Command<CommandInvocation> {

    /**
     * Set in the environment of the serve process to the id of the server it serves, so that a serve command which
     * (directly or not) runs {@code acp mcp serve <id>} again is detected instead of recursing forever.
     */
    static final String SERVING_ENV = "ACP_MCP_SERVING";

    @Option(name = "ready-timeout", description = "Seconds to wait for the url to accept connections before warning (default: 120)")
    Integer readyTimeout;

    @OptionList(shortName = 'e', name = "env", valueSeparator = McpCommandSupport.NO_SPLIT, description = "Environment variable passed to the serve command for this run, overriding the registered one: -e KEY=value (repeatable). ${VAR} references are expanded")
    List<String> env;

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
            if (isServing(System.getenv(SERVING_ENV), id)) {
                invocation.println("Error: the serve command of '" + id + "' runs 'acp mcp serve " + id
                        + "' again, which would loop forever.");
                invocation.println("Fix it with: acp mcp add -f -t " + server.transport() + " " + id + " "
                        + server.url() + " -- <command that really starts the server> [args...]");
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
            Map<String, String> serveEnv = environment(expanded.serve(), McpCommandSupport.parseEnv(env));
            pb.environment().putAll(serveEnv);
            String serving = System.getenv(SERVING_ENV);
            pb.environment().put(SERVING_ENV, addServing(serving, id));

            invocation.println("Serving MCP server '" + id + "': " + String.join(" ", command));
            if (!serveEnv.isEmpty()) {
                // only the names: the values may contain secrets
                invocation.println("  with env: " + String.join(", ", serveEnv.keySet()));
            }
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

    /**
     * The environment variables of the serve command: the registered ones, overridden by the ones passed with
     * {@code -e} (whose {@code ${VAR}} references are expanded).
     */
    static Map<String, String> environment(McpServeSpec serve, Map<String, String> overrides) {
        Map<String, String> result = new LinkedHashMap<>(serve.env());
        overrides.forEach((k, v) -> result.put(k, McpEnv.expand(v, System::getenv)));
        return result;
    }

    /**
     * Adds {@code id} to the list of the servers being served ({@value #SERVING_ENV}): comma separated, each id URL
     * encoded so that an id containing a comma is kept intact.
     */
    static String addServing(String serving, String id) {
        String encoded = URLEncoder.encode(id, StandardCharsets.UTF_8);
        return serving == null || serving.isEmpty() ? encoded : serving + "," + encoded;
    }

    /** Whether {@code id} is in the list of the servers already being served by ancestors (see {@link #addServing}). */
    static boolean isServing(String serving, String id) {
        if (serving == null || serving.isEmpty()) {
            return false;
        }
        for (String s : serving.split(",")) {
            if (URLDecoder.decode(s, StandardCharsets.UTF_8).equals(id)) {
                return true;
            }
        }
        return false;
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
