package io.smallrye.acp.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import com.fasterxml.jackson.databind.JsonNode;

import io.smallrye.acp.mcp.model.McpServer;
import io.smallrye.acp.mcp.probe.McpClient;
import io.smallrye.acp.mcp.registry.McpRegistry;

/**
 * Lists the tools exposed by a registered MCP server. A stdio server is started for the duration of the command; an
 * http server must be running (e.g. started with {@code acp mcp serve <id>}).
 *
 * <pre>{@code
 * acp mcp tools quarkit                 # name and description of each tool
 * acp mcp tools quarkit --schema        # ... and their parameters
 * acp mcp tools quarkit --json | jq -r '.[].name'
 * acp mcp tools quarkit-http --verbose  # print the JSON-RPC request/response
 * }</pre>
 */
@CommandDefinition(name = "tools", generateHelp = true, description = "List the tools exposed by an MCP server (stateless protocol "
        + McpClient.STATELESS_PROTOCOL_VERSION + ")")
public class McpToolsCommand implements Command<CommandInvocation> {

    @Option(name = "schema", hasValue = false, description = "Print the parameters of each tool (from its input schema); * marks the required ones")
    boolean schema;

    @Option(name = "json", hasValue = false, description = "Print the tools as a JSON array (as returned by tools/list)")
    boolean json;

    @Option(shortName = 'v', name = "verbose", hasValue = false, description = McpCommandSupport.VERBOSE_HELP)
    boolean verbose;

    @Option(name = "legacy", hasValue = false, description = McpCommandSupport.LEGACY_HELP)
    boolean legacy;

    @Option(name = "timeout", description = McpCommandSupport.REQUEST_TIMEOUT_HELP)
    Integer timeout;

    @Argument(required = true, description = "Id of the MCP server")
    String id;

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        McpServer server;
        try {
            server = new McpRegistry().require(id);
        } catch (Exception e) {
            invocation.println("Error: " + e.getMessage());
            return CommandResult.FAILURE;
        }
        long start = System.nanoTime();
        McpClient client = null;
        try {
            client = McpCommandSupport.connect(server, timeout, legacy);
            List<JsonNode> tools = client.listTools();
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            if (verbose) {
                McpCommandSupport.printTrace(invocation, client.trace());
                invocation.println("");
            }
            if (json) {
                invocation.println(McpCommandSupport.pretty(McpCommandSupport.MAPPER.valueToTree(tools)));
                return CommandResult.SUCCESS;
            }
            invocation.println(String.format("%s (%s) - %d tools in %d ms - %s", id, server.transport(), tools.size(),
                    elapsed, McpCommandSupport.protocol(client)));
            int width = tools.stream().mapToInt(t -> t.path("name").asText().length()).max().orElse(10) + 2;
            for (JsonNode tool : tools) {
                invocation.println(String.format("  %-" + width + "s %s", tool.path("name").asText(),
                        McpCommandSupport.truncate(McpCommandSupport.firstLine(tool.path("description").asText("")),
                                schema ? 200 : 100)));
                if (schema) {
                    parameters(tool.path("inputSchema")).forEach(p -> invocation.println("      " + p));
                }
            }
            invocation.println("");
            invocation.println("Call one with: acp mcp call " + id + " <tool> '{\"param\":\"value\"}'");
            return CommandResult.SUCCESS;
        } catch (Exception e) {
            return McpCommandSupport.failure(invocation, server, id, client, verbose, e);
        } finally {
            McpCommandSupport.closeQuietly(client);
        }
    }

    /** One line per property of the input schema: {@code name* type  description}. */
    static List<String> parameters(JsonNode inputSchema) {
        List<String> lines = new ArrayList<>();
        JsonNode properties = inputSchema.path("properties");
        if (!properties.isObject() || properties.isEmpty()) {
            lines.add("(no parameters)");
            return lines;
        }
        Set<String> required = new java.util.HashSet<>();
        inputSchema.path("required").forEach(r -> required.add(r.asText()));
        int width = 0;
        for (var it = properties.fieldNames(); it.hasNext();) {
            String name = it.next();
            width = Math.max(width, name.length() + (required.contains(name) ? 1 : 0));
        }
        int typeWidth = 0;
        for (JsonNode p : properties) {
            typeWidth = Math.max(typeWidth, type(p).length());
        }
        for (var it = properties.fields(); it.hasNext();) {
            var field = it.next();
            String name = field.getKey() + (required.contains(field.getKey()) ? "*" : "");
            JsonNode p = field.getValue();
            StringBuilder description = new StringBuilder(McpCommandSupport.firstLine(p.path("description").asText("")));
            if (p.has("enum")) {
                List<String> values = new ArrayList<>();
                p.get("enum").forEach(v -> values.add(v.asText()));
                description.append(description.isEmpty() ? "" : " ").append("[").append(String.join("|", values))
                        .append("]");
            }
            if (p.has("default")) {
                description.append(description.isEmpty() ? "" : " ").append("(default: ").append(p.get("default"))
                        .append(")");
            }
            lines.add(String.format("%-" + width + "s %-" + typeWidth + "s  %s", name, type(p),
                    McpCommandSupport.truncate(description.toString(), 120)).stripTrailing());
        }
        return lines;
    }

    private static String type(JsonNode property) {
        JsonNode type = property.path("type");
        if (type.isArray()) {
            List<String> types = new ArrayList<>();
            type.forEach(t -> types.add(t.asText()));
            return String.join("|", types);
        }
        if ("array".equals(type.asText()) && property.path("items").has("type")) {
            return property.path("items").path("type").asText() + "[]";
        }
        return type.asText("any");
    }
}
