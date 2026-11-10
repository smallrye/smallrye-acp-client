package io.smallrye.acp.mcp.probe;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.smallrye.acp.mcp.model.McpServer;

/**
 * Minimal MCP client used by {@code acp mcp list --check} and {@code acp mcp get --check}: it connects to a server,
 * performs the {@code initialize} handshake and calls {@code tools/list}.
 *
 * <p>
 * For a stdio server the probe starts the process itself (as the agent would do) and stops it afterwards. For an
 * http/sse server it only connects to the url: the server must be running.
 */
public final class McpProbe {

    public static final String PROTOCOL_VERSION = "2025-06-18";
    static final ObjectMapper MAPPER = new ObjectMapper();

    /** Receives the raw JSON-RPC traffic and transport events (HTTP status, process start, ...). */
    public interface Listener {
        Listener NONE = new Listener() {
        };

        default void outbound(String json) {
        }

        default void inbound(String json) {
        }

        default void info(String message) {
        }
    }

    public record Tool(String name, String description) {
    }

    /**
     * Result of a probe.
     *
     * @param up {@code true} when the handshake and {@code tools/list} succeeded
     * @param serverName {@code serverInfo.name}
     * @param serverVersion {@code serverInfo.version}
     * @param protocolVersion negotiated protocol version
     * @param tools tools exposed by the server
     * @param error failure reason when {@code up} is {@code false}
     * @param elapsed duration of the probe
     */
    public record Result(boolean up, String serverName, String serverVersion, String protocolVersion, List<Tool> tools,
            String error, Duration elapsed) {

        static Result failure(String error, long startNanos) {
            return new Result(false, null, null, null, List.of(), error, Duration.ofNanos(System.nanoTime() - startNanos));
        }
    }

    private McpProbe() {
    }

    /**
     * Probes a server whose variables are already expanded.
     *
     * @param server the server to probe
     * @param timeout maximum duration of each request (the stdio process start is included in the first one)
     * @param workDir working directory of a stdio process
     * @param listener receives the JSON-RPC traffic
     */
    public static Result probe(McpServer server, Duration timeout, Path workDir, Listener listener) {
        long start = System.nanoTime();
        AtomicLong ids = new AtomicLong();
        try (McpConnection connection = switch (server.transport()) {
            case STDIO -> new StdioMcpConnection(server, workDir, listener);
            case HTTP -> new HttpMcpConnection(server, timeout, listener);
            case SSE -> new SseMcpConnection(server, timeout, listener);
        }) {
            ObjectNode params = MAPPER.createObjectNode();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.putObject("capabilities");
            params.putObject("clientInfo").put("name", "acp").put("version", version());
            JsonNode init = result(connection.request(request(ids.incrementAndGet(), "initialize", params), timeout));

            connection.notify(notification("notifications/initialized"));

            List<Tool> tools = new ArrayList<>();
            String cursor = null;
            do {
                ObjectNode listParams = MAPPER.createObjectNode();
                if (cursor != null) {
                    listParams.put("cursor", cursor);
                }
                JsonNode list = result(connection.request(request(ids.incrementAndGet(), "tools/list", listParams),
                        timeout));
                for (JsonNode tool : list.path("tools")) {
                    tools.add(new Tool(tool.path("name").asText(), tool.path("description").asText("")));
                }
                cursor = list.hasNonNull("nextCursor") ? list.get("nextCursor").asText() : null;
            } while (cursor != null && !cursor.isEmpty());

            return new Result(true,
                    init.path("serverInfo").path("name").asText(null),
                    init.path("serverInfo").path("version").asText(null),
                    init.path("protocolVersion").asText(null),
                    tools, null, Duration.ofNanos(System.nanoTime() - start));
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (e instanceof java.net.ConnectException || e.getCause() instanceof java.net.ConnectException) {
                message = "connection refused";
            }
            return Result.failure(message, start);
        }
    }

    static ObjectNode request(long id, String method, ObjectNode params) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("id", id);
        node.put("method", method);
        node.set("params", params);
        return node;
    }

    static ObjectNode notification(String method) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("method", method);
        return node;
    }

    private static JsonNode result(JsonNode response) {
        if (response.has("error")) {
            JsonNode error = response.get("error");
            throw new IllegalStateException("JSON-RPC error " + error.path("code").asText() + ": "
                    + error.path("message").asText());
        }
        return response.path("result");
    }

    private static String version() {
        String v = McpProbe.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }
}
