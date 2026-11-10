package io.smallrye.acp.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Option;

import io.smallrye.acp.mcp.probe.McpProbe;
import io.smallrye.acp.mcp.registry.McpRegistry;

/**
 * Lists the registered MCP servers.
 *
 * <pre>{@code
 * acp mcp list
 * acp mcp list -s local
 * acp mcp list --check      # probe each server: start the stdio ones, connect to the http ones, call tools/list
 * }</pre>
 */
@CommandDefinition(name = "list", generateHelp = true, description = "List the registered MCP servers")
public class McpListCommand implements Command<CommandInvocation> {

    @Option(shortName = 's', name = "scope", description = "Only list this scope: home or local (both when omitted)")
    String scope;

    @Option(shortName = 'c', name = "check", hasValue = false, description = "Probe each server (initialize + tools/list) and show its status. stdio servers are started then stopped; http servers must be running")
    boolean check;

    @Option(name = "timeout", description = McpCommandSupport.TIMEOUT_HELP)
    Integer timeout;

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        try {
            var registry = new McpRegistry();
            var entries = registry.list(McpCommandSupport.scope(scope));
            if (entries.isEmpty()) {
                invocation.println("No MCP server registered. Add one with:");
                invocation.println("  acp mcp add <id> -- <command> [args...]           (stdio)");
                invocation.println("  acp mcp add -t http <id> <url> [-- <serve command>] (http)");
                return CommandResult.SUCCESS;
            }

            List<String> statuses = check ? probeAll(entries, McpCommandSupport.timeout(timeout)) : null;

            int nameWidth = Math.max(12, entries.stream().mapToInt(e -> e.name().length()).max().orElse(0) + 1);
            String format = "%-" + nameWidth + "s %-10s %-7s %-60s %-" + (check ? 40 : 1) + "s" + (check ? " %s" : "");
            List<Object> header = new ArrayList<>(List.of("NAME", "TRANSPORT", "SCOPE", "TARGET", "SERVE"));
            if (check) {
                header.add("STATUS");
            }
            invocation.println(String.format(format, header.toArray()).stripTrailing());
            invocation.println("-".repeat(check ? 160 : 120));

            boolean shadowed = false;
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.get(i);
                List<Object> row = new ArrayList<>();
                row.add(entry.name());
                if (entry.server() == null) {
                    row.addAll(List.of("?", entry.scope().id(), "invalid: " + entry.error(), "-"));
                } else {
                    var server = entry.server();
                    row.add(server.transport().id());
                    row.add(entry.scope().id() + (entry.shadowed() ? "*" : ""));
                    row.add(McpCommandSupport.truncate(server.target(), 60));
                    row.add(server.serve() != null ? "acp mcp serve " + server.name() : "-");
                }
                if (check) {
                    row.add(statuses.get(i));
                }
                shadowed |= entry.shadowed();
                invocation.println(String.format(format, row.toArray()).stripTrailing());
            }
            invocation.println("");
            if (shadowed) {
                invocation.println("* overridden by the local definition with the same name");
            }
            invocation.println("home: " + registry.file(io.smallrye.acp.mcp.model.McpScope.HOME));
            invocation.println("local: " + registry.file(io.smallrye.acp.mcp.model.McpScope.LOCAL));
            return CommandResult.SUCCESS;
        } catch (Exception e) {
            invocation.println("Error: " + e.getMessage());
            return CommandResult.FAILURE;
        }
    }

    private static List<String> probeAll(List<McpRegistry.Entry> entries, Duration timeout) throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = new ArrayList<>();
            for (var entry : entries) {
                futures.add(executor.submit(() -> {
                    if (entry.server() == null) {
                        return "invalid";
                    }
                    McpProbe.Result result = McpCommandSupport.probe(entry.server(), timeout);
                    return McpCommandSupport.status(result);
                }));
            }
            List<String> statuses = new ArrayList<>();
            for (Future<String> f : futures) {
                statuses.add(f.get());
            }
            return statuses;
        }
    }
}
