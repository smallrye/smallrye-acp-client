package io.smallrye.acp.mcp.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

import io.smallrye.acp.mcp.model.McpServeSpec;
import io.smallrye.acp.mcp.model.McpServer;
import io.smallrye.acp.mcp.model.McpTransport;

/**
 * JSON codec of {@link McpServer}.
 *
 * <p>
 * The acp registry layout is:
 *
 * <pre>{@code
 * { "mcpServers": {
 *     "quarkit":      { "type": "stdio", "command": "jbang", "args": ["mcp@quarkit"], "env": {} },
 *     "quarkit-http": { "type": "http", "url": "http://localhost:8080/mcp", "headers": {},
 *                       "serve": { "command": "jbang", "args": ["mcp@quarkit"] } } } }
 * }</pre>
 *
 * Reading is lenient so that files written for other tools can be imported ({@code acp mcp add-config-file}):
 * Claude/Bob/Gemini/Pi {@code mcpServers}, OpenCode {@code mcp}, Codex TOML {@code [mcp_servers.<name>]} and the ACP
 * array accepted by {@code acp run --mcp-server-config}.
 */
public final class McpServerJson {

    static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    private static final TomlMapper TOML = new TomlMapper();

    private McpServerJson() {
    }

    // -- Writing ----

    public static ObjectNode toJson(McpServer server) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", server.transport().id());
        if (server.transport() == McpTransport.STDIO) {
            node.put("command", server.command());
            node.set("args", array(server.args()));
            node.set("env", map(server.env()));
        } else {
            node.put("url", server.url());
            node.set("headers", map(server.headers()));
            if (server.serve() != null) {
                ObjectNode serve = node.putObject("serve");
                serve.put("command", server.serve().command());
                serve.set("args", array(server.serve().args()));
                if (!server.serve().env().isEmpty()) {
                    serve.set("env", map(server.serve().env()));
                }
            }
        }
        return node;
    }

    // -- Reading ----

    /** Parses one entry. Throws {@link IllegalArgumentException} when it cannot be understood. */
    public static McpServer fromJson(String name, JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("entry '" + name + "' is not an object");
        }
        String url = text(node, "url");
        String httpUrl = text(node, "httpUrl");
        JsonNode commandNode = node.get("command");

        McpTransport transport;
        String type = text(node, "type") != null ? text(node, "type") : text(node, "transport");
        if (type != null) {
            transport = McpTransport.from(type);
        } else if (httpUrl != null) {
            transport = McpTransport.HTTP;
        } else if (commandNode != null) {
            transport = McpTransport.STDIO;
        } else if (url != null) {
            transport = McpTransport.HTTP;
        } else {
            throw new IllegalArgumentException("entry '" + name + "' defines neither a command nor a url");
        }

        McpServer.Builder builder = McpServer.builder(name, transport);
        if (transport == McpTransport.STDIO) {
            if (commandNode != null && commandNode.isArray()) {
                List<String> command = strings(commandNode);
                if (command.isEmpty()) {
                    throw new IllegalArgumentException("entry '" + name + "' has an empty command");
                }
                builder.command(command.get(0)).args(command.subList(1, command.size()));
            } else {
                builder.command(text(node, "command"));
            }
            builder.args(strings(node.get("args")))
                    .env(stringMap(node.get("env")))
                    .env(stringMap(node.get("environment")));
        } else {
            builder.url(httpUrl != null && transport == McpTransport.HTTP ? httpUrl : url)
                    .headers(stringMap(node.get("headers")))
                    .headers(stringMap(node.get("http_headers")));
            String bearerVar = text(node, "bearer_token_env_var");
            if (bearerVar != null) {
                builder.header("Authorization", "Bearer ${" + bearerVar + "}");
            }
            JsonNode auth = node.get("auth");
            if (auth != null && "bearer".equalsIgnoreCase(auth.path("type").asText()) && auth.has("token")) {
                String token = auth.get("token").asText();
                builder.header("Authorization", "Bearer "
                        + (token.startsWith("env:") ? "${" + token.substring(4) + "}" : token));
            }
            JsonNode serve = node.get("serve");
            if (serve != null && serve.isObject()) {
                builder.serve(new McpServeSpec(text(serve, "command"), strings(serve.get("args")),
                        stringMap(serve.get("env"))));
            }
        }
        return builder.build();
    }

    /**
     * Parses MCP Config of an agent (file content or inline JSON) into MCP server(s).
     *
     * @param content JSON or TOML text
     * @param toml {@code true} to parse the content as TOML
     */
    public static List<McpServer> parseConfig(String content, boolean toml) throws java.io.IOException {
        JsonNode root = toml ? TOML.readTree(content) : MAPPER.readTree(content);
        List<McpServer> servers = new ArrayList<>();
        if (root == null) {
            return servers;
        }
        if (root.isArray()) {
            for (JsonNode entry : root) {
                servers.add(fromNamedEntry(entry));
            }
            return servers;
        }
        JsonNode map = null;
        if (root.has("mcpServers")) {
            map = root.get("mcpServers");
        } else if (root.has("mcp_servers")) {
            map = root.get("mcp_servers");
        } else if (root.has("mcp") && root.get("mcp").isObject()) {
            map = root.get("mcp").has("servers") ? root.get("mcp").get("servers") : root.get("mcp");
        } else if (root.has("name") && (root.has("command") || root.has("url"))) {
            servers.add(fromNamedEntry(root));
            return servers;
        }
        if (map == null || !map.isObject()) {
            throw new IllegalArgumentException("No MCP server found: expected 'mcpServers', 'mcp', 'mcp_servers', "
                    + "an ACP entry with a 'name' or an array of ACP entries");
        }
        map.fields().forEachRemaining(e -> servers.add(fromJson(e.getKey(), e.getValue())));
        return servers;
    }

    private static McpServer fromNamedEntry(JsonNode entry) {
        String name = text(entry, "name");
        if (name == null) {
            throw new IllegalArgumentException("ACP entry without 'name': " + entry);
        }
        return fromJson(name, entry);
    }

    // -- Helpers ----

    private static ArrayNode array(List<String> values) {
        ArrayNode node = MAPPER.createArrayNode();
        values.forEach(node::add);
        return node;
    }

    private static ObjectNode map(Map<String, String> values) {
        ObjectNode node = MAPPER.createObjectNode();
        values.forEach(node::put);
        return node;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.isContainerNode() ? null : value.asText();
    }

    private static List<String> strings(JsonNode value) {
        List<String> result = new ArrayList<>();
        if (value != null && value.isArray()) {
            value.forEach(v -> result.add(v.asText()));
        }
        return result;
    }

    /** Accepts {@code {"K": "V"}} as well as the ACP form {@code [{"name": "K", "value": "V"}]}. */
    private static Map<String, String> stringMap(JsonNode value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value != null && value.isObject()) {
            value.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().asText()));
        } else if (value != null && value.isArray()) {
            value.forEach(v -> {
                if (v.has("name")) {
                    result.put(v.get("name").asText(), v.path("value").asText());
                }
            });
        }
        return result;
    }
}
