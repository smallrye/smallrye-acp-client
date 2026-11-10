package io.smallrye.acp.mcp.probe;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.smallrye.acp.mcp.model.McpServer;

/**
 * Streamable HTTP transport: every message is POSTed to the endpoint; the response is either a JSON body or a
 * {@code text/event-stream} whose events carry the JSON-RPC response.
 */
class HttpMcpConnection implements McpConnection {

    private final HttpClient client;
    private final URI endpoint;
    private final Map<String, String> headers;
    private String sessionId;
    private String protocolVersion;

    HttpMcpConnection(McpServer server, Duration timeout) {
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.endpoint = URI.create(server.url());
        this.headers = server.headers();
    }

    @Override
    public JsonNode request(ObjectNode request, Duration timeout) throws Exception {
        HttpResponse<String> response = post(request, timeout);
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + " for '" + request.get("method").asText() + "'"
                    + (response.body().isBlank() ? "" : ": " + abbreviate(response.body())));
        }
        response.headers().firstValue("Mcp-Session-Id").ifPresent(id -> sessionId = id);
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        JsonNode message = contentType.startsWith("text/event-stream")
                ? SseEvents.findResponse(response.body(), request.get("id").asLong())
                : McpProbe.MAPPER.readTree(response.body());
        if (message == null) {
            throw new IOException("no JSON-RPC response for '" + request.get("method").asText() + "'");
        }
        if ("initialize".equals(request.get("method").asText())) {
            protocolVersion = message.path("result").path("protocolVersion").asText(null);
        }
        return message;
    }

    @Override
    public void notify(ObjectNode notification) throws Exception {
        HttpResponse<String> response = post(notification, Duration.ofSeconds(10));
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + " for '" + notification.get("method").asText()
                    + "'");
        }
    }

    private HttpResponse<String> post(ObjectNode message, Duration timeout) throws Exception {
        String json = McpProbe.MAPPER.writeValueAsString(message);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        headers.forEach(builder::header);
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        if (protocolVersion != null) {
            builder.header("MCP-Protocol-Version", protocolVersion);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String abbreviate(String s) {
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 197) + "...";
    }

    @Override
    public void close() {
        if (sessionId != null) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                        .header("Mcp-Session-Id", sessionId).DELETE();
                headers.forEach(builder::header);
                client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
                // best effort session termination
            }
        }
        client.close();
    }
}
