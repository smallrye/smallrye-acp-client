package io.smallrye.acp.mcp;

import java.util.List;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Arguments;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;

import io.smallrye.acp.mcp.model.McpScope;
import io.smallrye.acp.mcp.model.McpServeSpec;
import io.smallrye.acp.mcp.model.McpServer;
import io.smallrye.acp.mcp.model.McpTransport;
import io.smallrye.acp.mcp.registry.McpRegistry;

/**
 * Adds an MCP server to the acp registry, mirroring {@code claude mcp add}.
 *
 * <pre>{@code
 * # stdio: everything after -- is the command the agent starts
 * acp mcp add quarkit -- jbang -Dquarkus.http.host-enabled=false mcp@quarkit
 * acp mcp add -s local -e DEBUG=true fs -- npx -y @modelcontextprotocol/server-filesystem /tmp
 *
 * # http / sse: the agent connects to the url; what follows -- is how 'acp mcp serve' starts it locally
 * acp mcp add -t http quarkit-http http://localhost:8080/mcp -- jbang mcp-http@quarkit
 * acp mcp add -t http quarkit http://localhost:8080/mcp -- jbang mcp@quarkit
 * acp mcp add -t http github https://api.githubcopilot.com/mcp/ -H "Authorization: Bearer ${GITHUB_TOKEN}"
 * }</pre>
 */
@CommandDefinition(name = "add", generateHelp = true, description = "Add an MCP server: acp mcp add <id> -- <command> [args...] | acp mcp add -t http <id> <url> [-- <serve command> [args...]]")
public class McpAddCommand implements Command<CommandInvocation> {

    @Option(shortName = 't', name = "transport", defaultValue = "stdio", description = "Transport: stdio (default), http or sse")
    String transport;

    @Option(shortName = 's', name = "scope", description = McpCommandSupport.SCOPE_HELP)
    String scope;

    @OptionList(shortName = 'e', name = "env", valueSeparator = McpCommandSupport.NO_SPLIT, description = "Environment variable of the stdio server (or of the serve command of an http server): -e KEY=value (repeatable). ${VAR} references are expanded when used")
    List<String> env;

    @OptionList(shortName = 'H', name = "header", valueSeparator = McpCommandSupport.NO_SPLIT, description = "HTTP header sent to an http/sse server: -H \"Authorization: Bearer ${TOKEN}\" (repeatable)")
    List<String> headers;

    @Option(shortName = 'f', name = "force", hasValue = false, description = "Replace the server if it already exists")
    boolean force;

    @Arguments(description = "<id> -- <command> [args...] for stdio, <id> <url> [-- <serve command> [args...]] for http/sse")
    List<String> arguments;

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        try {
            McpServer server = buildServer();
            McpScope target = McpCommandSupport.scope(scope) != null ? McpCommandSupport.scope(scope) : McpScope.HOME;
            var registry = new McpRegistry();
            boolean existed = registry.find(target, server.name()).isPresent();
            if (existed && !force) {
                invocation.println("MCP server '" + server.name() + "' already exists in " + registry.file(target));
                invocation.println("Use --force to replace it, or remove it first: acp mcp remove " + server.name());
                return CommandResult.FAILURE;
            }
            registry.put(target, server);
            invocation.println(String.format("%s %s MCP server '%s' in %s",
                    existed ? "Replaced" : "Registered", server.transport(), server.name(), registry.file(target)));
            if (server.serve() != null) {
                invocation.println("  serve:   " + server.serve().commandLine());
                invocation.println("Start it with: acp mcp serve " + server.name());
            }
            invocation.println("Use it with:   acp run -a <agent> --mcp-server " + server.name() + " -p \"...\"");
            return CommandResult.SUCCESS;
        } catch (Exception e) {
            invocation.println("Error: " + e.getMessage());
            return CommandResult.FAILURE;
        }
    }

    McpServer buildServer() {
        McpTransport t = McpTransport.from(transport);
        List<String> args = arguments == null ? List.of() : arguments;
        var envVars = McpCommandSupport.parseEnv(env);
        var httpHeaders = McpCommandSupport.parseHeaders(headers);

        if (t == McpTransport.STDIO) {
            if (args.size() < 2) {
                throw new IllegalArgumentException("Usage: acp mcp add <id> -- <command> [args...]");
            }
            if (!httpHeaders.isEmpty()) {
                throw new IllegalArgumentException("--header is only valid for http/sse servers");
            }
            return McpServer.stdio(args.get(0), args.get(1))
                    .args(args.subList(2, args.size()))
                    .env(envVars)
                    .build();
        }
        if (args.size() < 2) {
            throw new IllegalArgumentException(
                    "Usage: acp mcp add -t " + t + " <id> <url> [-- <serve command> [args...]]");
        }
        String url = args.get(1);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("Invalid url '" + url + "': it must start with http:// or https://");
        }
        McpServeSpec serve = null;
        if (args.size() > 2) {
            serve = new McpServeSpec(args.get(2), args.subList(3, args.size()), envVars);
        } else if (!envVars.isEmpty()) {
            throw new IllegalArgumentException("--env requires a serve command for http/sse servers");
        }
        return McpServer.remote(args.get(0), t, url).headers(httpHeaders).serve(serve).build();
    }
}
