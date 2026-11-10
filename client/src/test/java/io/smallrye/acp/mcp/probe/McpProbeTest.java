package io.smallrye.acp.mcp.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.smallrye.acp.mcp.model.McpServer;
import io.smallrye.acp.mcp.model.McpTransport;

class McpProbeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer httpServer;

    @AfterEach
    void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    /** Records the traffic seen by the listener. */
    static final class Recorder implements McpProbe.Listener {
        final List<String> out = new CopyOnWriteArrayList<>();
        final List<String> in = new CopyOnWriteArrayList<>();

        @Override
        public void outbound(String json) {
            out.add(json);
        }

        @Override
        public void inbound(String json) {
            in.add(json);
        }
    }

    @Test
    void stdioServerIsStartedProbedAndStopped() {
        String java = ProcessHandle.current().info().command().orElse("java");
        McpServer server = McpServer.stdio("fake", java)
                .args(List.of("-cp", System.getProperty("java.class.path"), FakeStdioMcpServer.class.getName()))
                .env("FOO", "bar")
                .build();
        Recorder recorder = new Recorder();

        McpProbe.Result result = McpProbe.probe(server, Duration.ofSeconds(30), null, recorder);

        assertTrue(result.up(), result.error());
        assertEquals("fake-stdio", result.serverName());
        assertEquals("bar", result.serverVersion(), "env variables are passed to the process");
        assertEquals(List.of(new McpProbe.Tool("echo", "Echo a message")), result.tools());
        assertEquals(3, recorder.out.size(), "initialize, notifications/initialized, tools/list");
        assertTrue(recorder.out.get(1).contains("notifications/initialized"));
        assertTrue(recorder.in.stream().anyMatch(s -> s.contains("notifications/message")));
    }

    @Test
    void stdioCommandNotFound() {
        McpProbe.Result result = McpProbe.probe(McpServer.stdio("x", "does-not-exist-xyz").build(),
                Duration.ofSeconds(5), null, McpProbe.Listener.NONE);
        assertFalse(result.up());
        assertTrue(result.error().contains("does-not-exist-xyz"), result.error());
    }

    @Test
    void stdioProcessExitsWithStderr() {
        McpProbe.Result result = McpProbe.probe(McpServer.stdio("x", "sh").args(List.of("-c", "echo boom >&2; exit 3"))
                .build(), Duration.ofSeconds(10), null, McpProbe.Listener.NONE);
        assertFalse(result.up());
        assertTrue(result.error().contains("exited with code 3"), result.error());
        assertTrue(result.error().contains("boom"), result.error());
    }

    @Test
    void streamableHttpWithJsonAndSseResponses() throws IOException {
        List<String> sessionHeaders = new CopyOnWriteArrayList<>();
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/mcp", exchange -> {
            JsonNode msg = MAPPER.readTree(exchange.getRequestBody());
            sessionHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Mcp-Session-Id")));
            if ("DELETE".equals(exchange.getRequestMethod()) || !msg.has("id")) {
                respond(exchange, 202, null, "");
                return;
            }
            String id = msg.get("id").toString();
            switch (msg.get("method").asText()) {
                case "initialize" -> {
                    exchange.getResponseHeaders().add("Mcp-Session-Id", "s-1");
                    respond(exchange, 200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":" + id
                            + ",\"result\":{\"protocolVersion\":\"2025-06-18\",\"serverInfo\":{\"name\":\"fake-http\","
                            + "\"version\":\"1\"}}}");
                }
                case "tools/list" -> respond(exchange, 200, "text/event-stream",
                        "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":" + id
                                + ",\"result\":{\"tools\":[{\"name\":\"a\"},{\"name\":\"b\"}]}}\n\n");
                default -> respond(exchange, 400, null, "");
            }
        });
        httpServer.start();
        String url = "http://localhost:" + httpServer.getAddress().getPort() + "/mcp";

        Recorder recorder = new Recorder();
        McpProbe.Result result = McpProbe.probe(McpServer.remote("h", McpTransport.HTTP, url).build(),
                Duration.ofSeconds(10), null, recorder);

        assertTrue(result.up(), result.error());
        assertEquals("fake-http", result.serverName());
        assertEquals(List.of("a", "b"), result.tools().stream().map(McpProbe.Tool::name).toList());
        assertEquals("null", sessionHeaders.get(0));
        assertEquals("s-1", sessionHeaders.get(1), "the session id is sent after initialize");
        assertEquals(2, recorder.in.size());
    }

    @Test
    void httpServerDown() {
        McpProbe.Result result = McpProbe.probe(
                McpServer.remote("h", McpTransport.HTTP, "http://localhost:1/mcp").build(),
                Duration.ofSeconds(5), null, McpProbe.Listener.NONE);
        assertFalse(result.up());
        assertEquals("connection refused", result.error());
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
