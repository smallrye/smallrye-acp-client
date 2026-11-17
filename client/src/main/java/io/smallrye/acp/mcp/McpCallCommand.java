package io.smallrye.acp.mcp;

import java.util.List;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Arguments;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.smallrye.acp.mcp.model.McpServer;
import io.smallrye.acp.mcp.probe.McpClient;
import io.smallrye.acp.mcp.registry.McpRegistry;

/**
 * Calls a tool of a registered MCP server and prints its result. A stdio server is started for the duration of the
 * command; an http server must be running (e.g. started with {@code acp mcp serve <id>}).
 *
 * <pre>{@code
 * acp mcp call quarkit quarkit_reference_lookup '{"map":"annotation","key":"@Transactional","mode":"compat"}'
 * acp mcp call quarkit quarkit_reference_lookup -p map=annotation -p key=@Transactional -p mode=compat
 * acp mcp call quarkit-http some_tool -p limit=10 -p 'tags=["a","b"]'   # JSON values are parsed
 * acp mcp call quarkit some_tool --json | jq '.structuredContent'
 * }</pre>
 */
@CommandDefinition(name = "call", generateHelp = true, description = "Call a tool of an MCP server: acp mcp call <id> <tool> ['<arguments as JSON>'] [-p name=value...]")
public class McpCallCommand implements Command<CommandInvocation> {

    @OptionList(shortName = 'p', name = "param", valueSeparator = McpCommandSupport.NO_SPLIT, description = "Argument of the tool: -p name=value (repeatable), added to the JSON arguments. The value is parsed as JSON when valid (10, true, [\"a\"]...), used as a string otherwise")
    List<String> params;

    @Option(name = "json", hasValue = false, description = "Print the whole tool result as JSON (content, structuredContent, isError)")
    boolean json;

    @Option(shortName = 'v', name = "verbose", hasValue = false, description = McpCommandSupport.VERBOSE_HELP)
    boolean verbose;

    @Option(name = "legacy", hasValue = false, description = McpCommandSupport.LEGACY_HELP)
    boolean legacy;

    @Option(name = "timeout", description = McpCommandSupport.REQUEST_TIMEOUT_HELP)
    Integer timeout;

    @Arguments(description = "<id> <tool> ['<arguments as JSON>']")
    List<String> arguments;

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        if (arguments == null || arguments.size() < 2 || arguments.size() > 3) {
            invocation.println("Usage: acp mcp call <id> <tool> ['<arguments as JSON>'] [-p name=value...]");
            invocation.println("List the tools and their parameters with: acp mcp tools <id> --schema");
            return CommandResult.FAILURE;
        }
        String id = arguments.get(0);
        String tool = arguments.get(1);
        McpServer server;
        ObjectNode toolArguments;
        try {
            toolArguments = toolArguments(arguments.size() == 3 ? arguments.get(2) : null, params);
            server = new McpRegistry().require(id);
        } catch (Exception e) {
            invocation.println("Error: " + e.getMessage());
            return CommandResult.FAILURE;
        }
        McpClient client = null;
        try {
            client = McpCommandSupport.connect(server, timeout, legacy);
            JsonNode result = client.callTool(tool, toolArguments);
            if (verbose) {
                McpCommandSupport.printTrace(invocation, client.trace());
                invocation.println("");
            }
            boolean isError = result.path("isError").asBoolean(false);
            if (json) {
                invocation.println(McpCommandSupport.pretty(result));
            } else {
                if (isError) {
                    invocation.println("Tool '" + tool + "' returned an error:");
                }
                invocation.println(render(result));
            }
            return isError ? CommandResult.FAILURE : CommandResult.SUCCESS;
        } catch (Exception e) {
            CommandResult failure = McpCommandSupport.failure(invocation, server, id, client, verbose, e);
            if (e instanceof McpClient.McpErrorException error && error.code() == -32602) {
                invocation.println("Check the tool name and its parameters with: acp mcp tools " + id + " --schema");
            }
            return failure;
        } finally {
            McpCommandSupport.closeQuietly(client);
        }
    }

    /** Merges the JSON object argument and the {@code -p name=value} parameters (which win). */
    static ObjectNode toolArguments(String jsonArguments, List<String> params) {
        ObjectNode result;
        if (jsonArguments == null || jsonArguments.isBlank()) {
            result = McpCommandSupport.MAPPER.createObjectNode();
        } else {
            JsonNode parsed;
            try {
                parsed = McpCommandSupport.MAPPER.readTree(jsonArguments);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid JSON arguments '" + jsonArguments + "': "
                        + McpCommandSupport.firstLine(e.getMessage()));
            }
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException("The arguments must be a JSON object, e.g. '{\"name\":\"value\"}'");
            }
            result = (ObjectNode) parsed;
        }
        if (params != null) {
            for (String p : params) {
                int idx = p.indexOf('=');
                if (idx <= 0) {
                    throw new IllegalArgumentException("Invalid parameter '" + p + "', expected name=value");
                }
                result.set(p.substring(0, idx).trim(), value(p.substring(idx + 1)));
            }
        }
        return result;
    }

    private static JsonNode value(String raw) {
        try {
            JsonNode node = McpCommandSupport.MAPPER.readTree(raw);
            if (node != null && !node.isMissingNode()) {
                return node;
            }
        } catch (Exception ignored) {
            // not JSON: a plain string
        }
        return McpCommandSupport.MAPPER.getNodeFactory().textNode(raw);
    }

    /**
     * The content of a tool result: the text items as is, the other items summarized; the structured content when
     * there is no text.
     */
    static String render(JsonNode result) {
        StringBuilder out = new StringBuilder();
        for (JsonNode item : result.path("content")) {
            if (!out.isEmpty()) {
                out.append(System.lineSeparator());
            }
            switch (item.path("type").asText()) {
                case "text" -> out.append(item.path("text").asText());
                case "image", "audio" -> out.append("[").append(item.path("type").asText()).append(" ")
                        .append(item.path("mimeType").asText("")).append(", ")
                        .append(item.path("data").asText("").length()).append(" base64 chars]");
                case "resource_link" -> out.append("[resource link] ").append(item.path("uri").asText());
                case "resource" -> {
                    JsonNode resource = item.path("resource");
                    out.append("[resource] ").append(resource.path("uri").asText());
                    if (resource.has("text")) {
                        out.append(System.lineSeparator()).append(resource.path("text").asText());
                    }
                }
                default -> out.append(McpCommandSupport.pretty(item));
            }
        }
        if (result.has("structuredContent")
                && java.util.stream.StreamSupport.stream(result.path("content").spliterator(), false)
                        .noneMatch(item -> "text".equals(item.path("type").asText())
                                && !item.path("text").asText().isEmpty())) {
            if (!out.isEmpty()) {
                out.append(System.lineSeparator());
            }
            out.append(McpCommandSupport.pretty(result.get("structuredContent")));
        }
        return out.isEmpty() ? "(empty result)" : out.toString();
    }
}
