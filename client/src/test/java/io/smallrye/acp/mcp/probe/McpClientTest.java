package io.smallrye.acp.mcp.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.smallrye.acp.mcp.model.McpServer;
import io.smallrye.acp.mcp.model.McpTransport;

class McpClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private HttpServer httpServer;
    private final List<Map<String, String>> requestHeaders = new CopyOnWriteArrayList<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void statelessHttpListAndCallInOneRequestEach() throws Exception {
        String url = startHttp(false);
        try (McpClient client = McpClient.open(McpServer.remote("h", McpTransport.HTTP, url).build(), TIMEOUT, null,
                false)) {
            List<JsonNode> tools = client.listTools();
            assertEquals(List.of("echo"), tools.stream().map(t -> t.path("name").asText()).toList());
            JsonNode result = client.callTool("echo", MAPPER.readTree("{\"message\":\"hi\"}"));
            assertEquals("echo: hi", result.path("content").get(0).path("text").asText());
            assertTrue(client.stateless());
            assertEquals(McpClient.STATELESS_PROTOCOL_VERSION, client.protocolVersion());
            assertNull(client.serverName());
        }
        assertEquals(List.of("tools/list", "tools/call"), requests.stream().map(r -> r.path("method").asText())
                .toList(), "no initialize handshake, no session termination");
        JsonNode meta = requests.get(0).path("params").path("_meta");
        assertEquals(McpClient.STATELESS_PROTOCOL_VERSION, meta.path(McpClient.META_PROTOCOL_VERSION).asText());
        assertEquals("acp", meta.path(McpClient.META_CLIENT_INFO).path("name").asText());
        assertTrue(meta.path(McpClient.META_CLIENT_CAPABILITIES).isObject());
        assertEquals(McpClient.STATELESS_PROTOCOL_VERSION, requestHeaders.get(0).get("mcp-protocol-version"));
        assertEquals("tools/list", requestHeaders.get(0).get("mcp-method"));
        assertNull(requestHeaders.get(0).get("mcp-name"));
        assertEquals("tools/call", requestHeaders.get(1).get("mcp-method"));
        assertEquals("echo", requestHeaders.get(1).get("mcp-name"));
    }

    @Test
    void legacyHttpServerFallsBackToInitialize() throws Exception {
        String url = startHttp(true);
        try (McpClient client = McpClient.open(McpServer.remote("h", McpTransport.HTTP, url).build(), TIMEOUT, null,
                false)) {
            assertEquals(1, client.listTools().size());
            JsonNode result = client.callTool("echo", MAPPER.readTree("{\"message\":\"hi\"}"));
            assertEquals("echo: hi", result.path("content").get(0).path("text").asText());
            assertFalse(client.stateless());
            assertEquals("2025-06-18", client.protocolVersion());
            assertEquals("fake-http", client.serverName());
        }
        assertEquals(List.of("tools/list", "initialize", "notifications/initialized", "tools/list", "tools/call"),
                requests.stream().map(r -> r.path("method").asText()).toList());
        assertFalse(requests.get(3).path("params").has("_meta"), "no stateless _meta once in legacy mode");
        assertEquals("s-1", requestHeaders.get(4).get("mcp-session-id"));
        assertEquals("2025-06-18", requestHeaders.get(4).get("mcp-protocol-version"));
    }

    @Test
    void statelessStdio() throws Exception {
        try (McpClient client = McpClient.open(fakeStdio(false), TIMEOUT, null, false)) {
            JsonNode result = client.callTool("echo", MAPPER.readTree("{\"message\":\"stdio\"}"));
            assertEquals("echo: stdio", result.path("content").get(0).path("text").asText());
            assertTrue(client.stateless());
            assertEquals(List.of("tools/list", "tools/call"), client.trace().stream().filter(McpProbe.Message::sent)
                    .map(msg -> msg.json().replaceAll(".*\"method\":\"([^\"]+)\".*", "$1")).toList(),
                    "the protocol is determined with tools/list before calling the tool");
        }
    }

    @Test
    void strictStdioServerFallsBackToInitialize() throws Exception {
        try (McpClient client = McpClient.open(fakeStdio(true), TIMEOUT, null, false)) {
            assertEquals(1, client.listTools().size());
            assertFalse(client.stateless());
            assertEquals("fake-stdio", client.serverName());
            assertEquals(List.of(true, false, true, false, true, true, false),
                    client.trace().stream().map(McpProbe.Message::sent).toList(),
                    "rejected tools/list, initialize, notifications/initialized, tools/list");
        }
    }

    @Test
    void legacyFlagSkipsTheStatelessRequest() throws Exception {
        try (McpClient client = McpClient.open(fakeStdio(false), TIMEOUT, null, true)) {
            client.listTools();
            assertFalse(client.stateless());
            assertTrue(client.trace().get(0).json().contains("\"initialize\""));
        }
    }

    @Test
    void errorOfAStatelessServerIsReportedWhenTheHandshakeIsRejectedToo() throws Exception {
        String url = startHttp(false);
        try (McpClient client = McpClient.open(McpServer.remote("h", McpTransport.HTTP, url).build(), TIMEOUT, null,
                false)) {
            var e = assertThrows(McpClient.McpErrorException.class,
                    () -> client.callTool("unknown", MAPPER.createObjectNode()));
            assertEquals(-32602, e.code());
            assertTrue(e.getMessage().contains("Unknown tool"), e.getMessage());
        }
    }

    @Test
    void failedToolCallIsNeverReplayed() throws Exception {
        // a server accepting both protocols, whose tool fails with an internal error (after possible side effects)
        String url = startHttp(false, true);
        try (McpClient client = McpClient.open(McpServer.remote("h", McpTransport.HTTP, url).build(), TIMEOUT, null,
                false)) {
            var e = assertThrows(McpClient.McpErrorException.class,
                    () -> client.callTool("crash", MAPPER.createObjectNode()));
            assertEquals(-32603, e.code());
        }
        assertEquals(List.of("tools/list", "tools/call"), requests.stream().map(r -> r.path("method").asText())
                .toList(), "no handshake and no second tools/call");
    }

    private static McpServer fakeStdio(boolean strict) {
        String java = ProcessHandle.current().info().command().orElse("java");
        return McpServer.stdio("fake", java)
                .args(List.of("-cp", System.getProperty("java.class.path"), FakeStdioMcpServer.class.getName()))
                .env("STRICT", String.valueOf(strict))
                .build();
    }

    /**
     * A stateless server (rejects initialize, requires the _meta protocol version) or a legacy one (rejects the
     * requests without session with a 400, like the session based SDKs).
     */
    private String startHttp(boolean legacy) throws IOException {
        return startHttp(legacy, false);
    }

    /** @param dual a stateless server which accepts the initialize handshake too */
    private String startHttp(boolean legacy, boolean dual) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/mcp", exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                respond(exchange, 204, null, "");
                return;
            }
            JsonNode msg = MAPPER.readTree(exchange.getRequestBody());
            requests.add(msg);
            Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
            requestHeaders.add(headers);
            if (!msg.has("id")) {
                respond(exchange, 202, null, "");
                return;
            }
            String id = msg.get("id").toString();
            String method = msg.get("method").asText();
            if (legacy && !"initialize".equals(method) && !"s-1".equals(headers.get("mcp-session-id"))) {
                respond(exchange, 400, "text/plain", "Bad Request: No valid session ID provided");
                return;
            }
            if (!legacy && !(dual && "initialize".equals(method)) && !McpClient.STATELESS_PROTOCOL_VERSION.equals(
                    msg.path("params").path("_meta").path(McpClient.META_PROTOCOL_VERSION).asText())) {
                respond(exchange, 200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"error\":{\"code\":-32600,\"message\":\"Unsupported protocol version\"}}");
                return;
            }
            String body = switch (method) {
                case "initialize" -> {
                    exchange.getResponseHeaders().add("Mcp-Session-Id", "s-1");
                    yield "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":\"2025-06-18\","
                            + "\"serverInfo\":{\"name\":\"fake-http\",\"version\":\"1\"}}}";
                }
                case "tools/list" -> "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"tools\":[{\"name\":\"echo\",\"inputSchema\":{\"type\":\"object\"}}]}}";
                case "tools/call" -> "crash".equals(msg.path("params").path("name").asText())
                        ? "{\"jsonrpc\":\"2.0\",\"id\":" + id
                                + ",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}"
                        : "echo".equals(msg.path("params").path("name").asText())
                                ? "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{\"type\":\"text\","
                                        + "\"text\":\"echo: " + msg.path("params").path("arguments").path("message").asText()
                                        + "\"}]}}"
                                : "{\"jsonrpc\":\"2.0\",\"id\":" + id
                                        + ",\"error\":{\"code\":-32602,\"message\":\"Unknown tool\"}}";
                default -> "{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}";
            };
            respond(exchange, 200, "application/json", body);
        });
        httpServer.start();
        return "http://localhost:" + httpServer.getAddress().getPort() + "/mcp";
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (contentType != null) {
            exchange.getResponseHeaders().add("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
