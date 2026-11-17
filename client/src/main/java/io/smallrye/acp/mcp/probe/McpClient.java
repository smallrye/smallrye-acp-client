package io.smallrye.acp.mcp.probe;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.smallrye.acp.mcp.model.McpServer;

/**
 * MCP client used by {@code acp mcp tools} and {@code acp mcp call}.
 *
 * <p>
 * It first talks the <b>stateless</b> protocol {@value #STATELESS_PROTOCOL_VERSION}: no {@code initialize} handshake
 * and no session, every request carries the protocol version, the client info and its capabilities in
 * {@code params._meta} (and, over HTTP, the {@code MCP-Protocol-Version}, {@code Mcp-Method} and {@code Mcp-Name}
 * headers). A single request is then enough to list the tools (two to call one, see below).
 *
 * <p>
 * When the server rejects the first stateless request (JSON-RPC error or HTTP 4xx), the client falls back to the
 * classic handshake ({@code initialize} with {@value McpProbe#PROTOCOL_VERSION} + {@code notifications/initialized})
 * and resends the request. {@link #open(McpServer, Duration, Path, boolean) legacy} forces this mode.
 *
 * <p>
 * Only read-only requests are ever resent: before the first {@code tools/call}, the protocol is determined with a
 * {@code tools/list} request, so that a tool with side effects is never executed twice (e.g. when it fails with an
 * internal error on a server accepting both protocols).
 */
public final class McpClient implements AutoCloseable {

    public static final String STATELESS_PROTOCOL_VERSION = "2026-07-28";
    static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
    static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";
    static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";

    /** A JSON-RPC error returned by the server. */
    public static final class McpErrorException extends Exception {
        private final int code;

        McpErrorException(JsonNode error) {
            super("JSON-RPC error " + error.path("code").asText() + ": " + error.path("message").asText());
            this.code = error.path("code").asInt();
        }

        public int code() {
            return code;
        }
    }

    private enum Mode {
        UNKNOWN,
        STATELESS,
        LEGACY
    }

    private final McpConnection connection;
    private final Duration timeout;
    private final List<McpProbe.Message> trace = new ArrayList<>();
    private long ids;
    private Mode mode;
    private String protocolVersion;
    private String serverName;
    private String serverVersion;

    private McpClient(McpConnection connection, Duration timeout, boolean legacy) {
        this.connection = connection;
        this.timeout = timeout;
        this.mode = legacy ? Mode.LEGACY : Mode.UNKNOWN;
    }

    /**
     * Connects to a server whose variables are already expanded (starting the process of a stdio server).
     *
     * @param legacy {@code true} to skip the stateless protocol and perform the {@code initialize} handshake
     */
    public static McpClient open(McpServer server, Duration timeout, Path workDir, boolean legacy) throws Exception {
        return new McpClient(McpConnection.open(server, timeout, workDir), timeout, legacy);
    }

    /** Returns the tools exposed by the server (all the pages of {@code tools/list}). */
    public List<JsonNode> listTools() throws Exception {
        List<JsonNode> tools = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode params = McpProbe.MAPPER.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = request("tools/list", params);
            result.path("tools").forEach(tools::add);
            cursor = result.hasNonNull("nextCursor") ? result.get("nextCursor").asText() : null;
        } while (cursor != null && !cursor.isEmpty());
        return tools;
    }

    /**
     * Calls a tool and returns the {@code CallToolResult} ({@code content}, {@code structuredContent},
     * {@code isError}).
     */
    public JsonNode callTool(String name, JsonNode arguments) throws Exception {
        if (mode == Mode.UNKNOWN) {
            // determine the protocol with a read-only request: a failed tools/call must never be replayed
            request("tools/list", McpProbe.MAPPER.createObjectNode());
        }
        ObjectNode params = McpProbe.MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments != null ? arguments : McpProbe.MAPPER.createObjectNode());
        return request("tools/call", params);
    }

    /**
     * Sends a request and returns its {@code result}.
     *
     * @throws McpErrorException when the server answers with a JSON-RPC error
     */
    public JsonNode request(String method, ObjectNode params) throws Exception {
        if (mode == Mode.LEGACY) {
            initialize();
            return result(send(method, params.deepCopy()));
        }
        JsonNode response;
        try {
            response = send(method, withMeta(params));
        } catch (McpHttpStatusException e) {
            if (mode == Mode.UNKNOWN && e.status() / 100 == 4) {
                return fallback(method, params, e);
            }
            throw e;
        }
        if (response.has("error") && mode == Mode.UNKNOWN) {
            return fallback(method, params, new McpErrorException(response.get("error")));
        }
        mode = Mode.STATELESS;
        protocolVersion = STATELESS_PROTOCOL_VERSION;
        return result(response);
    }

    /**
     * The server rejected the stateless request: try the handshake, and if the server does not accept it either,
     * report the original failure (e.g. an unknown tool) rather than the handshake one.
     */
    private JsonNode fallback(String method, ObjectNode params, Exception statelessFailure) throws Exception {
        try {
            initialize();
        } catch (Exception e) {
            throw statelessFailure;
        }
        mode = Mode.LEGACY;
        return result(send(method, params.deepCopy()));
    }

    private void initialize() throws Exception {
        if (protocolVersion != null) {
            return;
        }
        ObjectNode params = McpProbe.MAPPER.createObjectNode();
        params.put("protocolVersion", McpProbe.PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.set("clientInfo", clientInfo());
        JsonNode init = result(send("initialize", params));
        ObjectNode initialized = McpProbe.notification("notifications/initialized");
        trace.add(new McpProbe.Message(true, initialized.toString()));
        connection.notify(initialized);
        protocolVersion = init.path("protocolVersion").asText(McpProbe.PROTOCOL_VERSION);
        serverName = init.path("serverInfo").path("name").asText(null);
        serverVersion = init.path("serverInfo").path("version").asText(null);
    }

    private ObjectNode withMeta(ObjectNode params) {
        ObjectNode copy = params.deepCopy();
        ObjectNode meta = copy.has("_meta") && copy.get("_meta").isObject() ? (ObjectNode) copy.get("_meta")
                : copy.putObject("_meta");
        meta.put(META_PROTOCOL_VERSION, STATELESS_PROTOCOL_VERSION);
        meta.set(META_CLIENT_INFO, clientInfo());
        meta.putObject(META_CLIENT_CAPABILITIES);
        return copy;
    }

    private static ObjectNode clientInfo() {
        return McpProbe.MAPPER.createObjectNode().put("name", "acp").put("version", McpProbe.version());
    }

    private JsonNode send(String method, ObjectNode params) throws Exception {
        ObjectNode request = McpProbe.request(++ids, method, params);
        trace.add(new McpProbe.Message(true, request.toString()));
        JsonNode response = connection.request(request, timeout);
        trace.add(new McpProbe.Message(false, response.toString()));
        return response;
    }

    private static JsonNode result(JsonNode response) throws McpErrorException {
        if (response.has("error")) {
            throw new McpErrorException(response.get("error"));
        }
        return response.path("result");
    }

    /** {@code true} when the server accepted the stateless protocol. */
    public boolean stateless() {
        return mode == Mode.STATELESS;
    }

    /** The protocol version used: {@value #STATELESS_PROTOCOL_VERSION} or the one negotiated by initialize. */
    public String protocolVersion() {
        return protocolVersion;
    }

    /** {@code serverInfo.name}, only known after an {@code initialize} handshake. */
    public String serverName() {
        return serverName;
    }

    public String serverVersion() {
        return serverVersion;
    }

    /** The JSON-RPC messages exchanged so far, in order. */
    public List<McpProbe.Message> trace() {
        return List.copyOf(trace);
    }

    @Override
    public void close() throws Exception {
        connection.close();
    }
}
