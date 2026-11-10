package io.smallrye.acp.mcp.probe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.smallrye.acp.mcp.model.McpServer;

/**
 * Starts a stdio MCP server and exchanges newline-delimited JSON-RPC messages over its stdin/stdout.
 */
class StdioMcpConnection implements McpConnection {

    private static final int STDERR_LINES = 20;

    private final Process process;
    private final OutputStream stdin;
    private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
    private final Deque<String> stderrTail = new ArrayDeque<>();
    private final McpProbe.Listener listener;

    StdioMcpConnection(McpServer server, Path workDir, McpProbe.Listener listener) throws IOException {
        this.listener = listener;
        List<String> command = new ArrayList<>();
        command.add(server.command());
        command.addAll(server.args());
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        pb.environment().putAll(server.env());
        listener.info("Starting: " + String.join(" ", command));
        this.process = pb.start();
        this.stdin = process.getOutputStream();

        Thread.ofVirtual().start(() -> readStdout(new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))));
        Thread.ofVirtual().start(() -> readStderr(new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))));
    }

    private void readStdout(BufferedReader reader) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("{")) {
                    continue; // not a JSON-RPC message (some servers print banners on stdout)
                }
                try {
                    JsonNode node = McpProbe.MAPPER.readTree(trimmed);
                    listener.inbound(trimmed);
                    messages.add(node);
                } catch (IOException ignored) {
                    // malformed line, skip
                }
            }
        } catch (IOException ignored) {
            // stream closed
        }
    }

    private void readStderr(BufferedReader reader) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (stderrTail) {
                    if (stderrTail.size() == STDERR_LINES) {
                        stderrTail.removeFirst();
                    }
                    stderrTail.addLast(line);
                }
            }
        } catch (IOException ignored) {
            // stream closed
        }
    }

    @Override
    public JsonNode request(ObjectNode request, Duration timeout) throws Exception {
        send(request);
        long id = request.get("id").asLong();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            JsonNode message = remaining > 0 ? messages.poll(Math.min(remaining, 200_000_000L), TimeUnit.NANOSECONDS)
                    : null;
            if (message != null && message.has("id") && message.get("id").asLong() == id
                    && (message.has("result") || message.has("error"))) {
                return message;
            }
            if (message == null && !process.isAlive() && messages.isEmpty()) {
                throw new IOException("process exited with code " + process.exitValue() + stderrHint());
            }
            if (remaining <= 0) {
                throw new TimeoutException("no response to '" + request.get("method").asText() + "' after "
                        + timeout.toSeconds() + "s" + stderrHint());
            }
        }
    }

    @Override
    public void notify(ObjectNode notification) throws Exception {
        send(notification);
    }

    private void send(ObjectNode message) throws IOException {
        String json = McpProbe.MAPPER.writeValueAsString(message);
        listener.outbound(json);
        stdin.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private String stderrHint() {
        synchronized (stderrTail) {
            if (stderrTail.isEmpty()) {
                return "";
            }
            return System.lineSeparator() + "  last stderr lines:" + System.lineSeparator() + "    "
                    + String.join(System.lineSeparator() + "    ", stderrTail);
        }
    }

    @Override
    public void close() {
        try {
            stdin.close();
        } catch (IOException ignored) {
            // already closed
        }
        // wrappers such as jbang or npx start the real server as a child process
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        listener.info("Stopped the MCP server process");
    }
}
