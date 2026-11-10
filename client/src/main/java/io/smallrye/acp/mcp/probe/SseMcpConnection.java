package io.smallrye.acp.mcp.probe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.smallrye.acp.mcp.model.McpServer;

/**
 * Legacy HTTP + SSE transport: a GET opens the event stream whose first {@code endpoint} event gives the url where
 * messages are POSTed; responses come back as {@code message} events on the stream.
 */
class SseMcpConnection implements McpConnection {

    private final HttpClient client;
    private final Map<String, String> headers;
    private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
    private final CompletableFuture<URI> endpoint = new CompletableFuture<>();
    private final InputStream stream;

    SseMcpConnection(McpServer server, Duration timeout) throws Exception {
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.headers = server.headers();
        URI url = URI.create(server.url());

        HttpRequest.Builder builder = HttpRequest.newBuilder(url).header("Accept", "text/event-stream").GET();
        headers.forEach(builder::header);
        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode() + " when opening the SSE stream");
        }
        this.stream = response.body();
        Thread.ofVirtual().start(() -> read(url));
        try {
            endpoint.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            close();
            throw new TimeoutException("no 'endpoint' event received on the SSE stream");
        }
    }

    private void read(URI base) {
        SseEvents.Parser parser = new SseEvents.Parser();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                SseEvents.Event event = parser.accept(line);
                if (event == null) {
                    continue;
                }
                if ("endpoint".equals(event.name())) {
                    endpoint.complete(base.resolve(event.data().trim()));
                } else if (event.data().trim().startsWith("{")) {
                    messages.add(McpProbe.MAPPER.readTree(event.data()));
                }
            }
        } catch (IOException ignored) {
            // stream closed
        }
    }

    @Override
    public JsonNode request(ObjectNode request, Duration timeout) throws Exception {
        post(request, timeout);
        long id = request.get("id").asLong();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode message = messages.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (message != null && message.has("id") && message.get("id").asLong() == id) {
                return message;
            }
        }
        throw new TimeoutException("no response to '" + request.get("method").asText() + "' after "
                + timeout.toSeconds() + "s");
    }

    @Override
    public void notify(ObjectNode notification) throws Exception {
        post(notification, Duration.ofSeconds(10));
    }

    private void post(ObjectNode message, Duration timeout) throws Exception {
        String json = McpProbe.MAPPER.writeValueAsString(message);
        URI url = endpoint.getNow(null);
        HttpRequest.Builder builder = HttpRequest.newBuilder(url).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        headers.forEach(builder::header);
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + " for '" + message.get("method").asText() + "'");
        }
    }

    @Override
    public void close() {
        try {
            stream.close();
        } catch (IOException ignored) {
            // already closed
        }
        client.close();
    }
}
