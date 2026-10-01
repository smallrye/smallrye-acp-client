package io.smallrye.agentclientprotocol.sdk.test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.smallrye.agentclientprotocol.sdk.client.transport.AcpTransport;

/**
 * A scripted ACP agent peer for test.
 *
 * <p>
 * Simulates the agent side of the ACP protocol using in-process pipes.
 * Register handlers for JSON-RPC methods, then wire the client to
 * {@link #transport()}. The scripted agent responds to requests and can
 * push notifications or agent-originated requests.
 *
 * <p>
 * Example:
 *
 * <pre>{@code
 * try (MockAcpAgent agent = MockAcpAgent.start()) {
 *     agent.on("initialize", params -> new InitializeResponse(1));
 *     agent.on("session/new", params -> new NewSessionResponse("s1"));
 *
 *     AcpAsyncClient client = AcpClient.async(agent.transport())
 *             .withRequestTimeout(Duration.ofSeconds(2))
 *             .build();
 *     client.connect().join();
 *     client.initialize().join();
 *
 *     JsonNode sent = agent.requests().getFirst();
 *     assertEquals("initialize", sent.get("method").asText());
 * }
 * }</pre>
 */
public class MockAcpAgent implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(MockAcpAgent.class);
    private static final int PIPE_BUFFER_SIZE = 65536;

    private final PipeAcpTransport clientTransport;
    private final PipedOutputStream agentToClientOut;
    private final PipedInputStream clientToAgentIn;
    private final ObjectMapper mapper;

    private final ConcurrentHashMap<String, Function<JsonNode, Object>> handlers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<JsonNode> capturedRequests = new CopyOnWriteArrayList<>();
    private final ExecutorService agentReaderExecutor;
    private volatile boolean closed = false;

    private MockAcpAgent(PipeAcpTransport clientTransport,
            PipedOutputStream agentToClientOut,
            PipedInputStream clientToAgentIn,
            ObjectMapper mapper) {
        this.clientTransport = clientTransport;
        this.agentToClientOut = agentToClientOut;
        this.clientToAgentIn = clientToAgentIn;
        this.mapper = mapper;

        this.agentReaderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "scripted-agent-reader");
            t.setDaemon(true);
            return t;
        });

        agentReaderExecutor.execute(this::agentReadLoop);
    }

    /**
     * Creates and starts a new scripted agent with pipe-connected transport.
     *
     * @return a started agent ready for handler registration
     */
    public static MockAcpAgent start() {
        try {
            // Pipe pair 1: client -> agent
            PipedOutputStream clientToAgentOut = new PipedOutputStream();
            PipedInputStream clientToAgentIn = new PipedInputStream(clientToAgentOut, PIPE_BUFFER_SIZE);

            // Pipe pair 2: agent -> client
            PipedOutputStream agentToClientOut = new PipedOutputStream();
            PipedInputStream agentToClientIn = new PipedInputStream(agentToClientOut, PIPE_BUFFER_SIZE);

            ObjectMapper mapper = AcpTransport.createDefaultMapper();

            PipeAcpTransport clientTransport = new PipeAcpTransport(agentToClientIn, clientToAgentOut, mapper);

            return new MockAcpAgent(clientTransport, agentToClientOut, clientToAgentIn, mapper);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create pipe transport", e);
        }
    }

    /**
     * Returns the client-side transport. Pass this to
     * {@link io.smallrye.agentclientprotocol.sdk.client.AcpClient#async(AcpTransport)} or
     * {@link io.smallrye.agentclientprotocol.sdk.client.AcpClient#sync(AcpTransport)}.
     *
     * @return the pipe-based transport for the client
     */
    public AcpTransport transport() {
        return clientTransport;
    }

    /**
     * Registers a handler for a JSON-RPC method. When the client sends a request
     * with this method name, the handler is invoked with the {@code "params"} node
     * and its return value becomes the {@code "result"} in the JSON-RPC response.
     *
     * @param method the JSON-RPC method name (e.g. {@code "initialize"}, {@code "session/new"})
     * @param handler a function from params to the response object
     * @return this agent for chaining
     */
    public MockAcpAgent on(String method, Function<JsonNode, Object> handler) {
        handlers.put(method, handler);
        return this;
    }

    /**
     * Returns an unmodifiable snapshot of all JSON-RPC messages received from the client,
     * in order of receipt. Includes both requests and notifications.
     *
     * @return the captured messages
     */
    public List<JsonNode> requests() {
        return List.copyOf(capturedRequests);
    }

    /**
     * Sends a JSON-RPC notification from the agent to the client.
     *
     * @param method the notification method (e.g. {@code "session/update"})
     * @param params the notification parameters
     */
    public void sendNotification(String method, Object params) {
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.set("params", mapper.valueToTree(params));
        try {
            writeLine(mapper.writeValueAsString(notification));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to send notification", e);
        }
    }

    /**
     * Sends a JSON-RPC request from the agent to the client
     * (e.g. {@code session/request_permission}).
     *
     * @param id the request id
     * @param method the request method
     * @param params the request parameters
     */
    public void sendRequest(int id, String method, Object params) {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", mapper.valueToTree(params));
        try {
            writeLine(mapper.writeValueAsString(request));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to send agent request", e);
        }
    }

    @Override
    public void close() {
        closed = true;
        clientTransport.closeGracefully();
        try {
            agentToClientOut.close();
        } catch (IOException ignored) {
        }
        try {
            clientToAgentIn.close();
        } catch (IOException ignored) {
        }
        agentReaderExecutor.shutdownNow();
    }

    private void agentReadLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientToAgentIn))) {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                try {
                    JsonNode request = mapper.readTree(line);
                    capturedRequests.add(request);

                    if (request.has("id") && request.has("method")) {
                        String method = request.get("method").asText();
                        JsonNode id = request.get("id");
                        JsonNode params = request.get("params");

                        Function<JsonNode, Object> handler = handlers.get(method);
                        if (handler != null) {
                            Object result = handler.apply(params);
                            writeResponse(id, result);
                        } else {
                            writeErrorResponse(id, -32601, "No handler registered for: " + method);
                        }
                    }
                } catch (Exception e) {
                    if (!closed) {
                        logger.errorf(e, "Error in scripted agent read loop");
                    }
                }
            }
        } catch (IOException e) {
            if (!closed) {
                logger.error("Error reading from client-to-agent pipe", e);
            }
        }
    }

    private void writeResponse(JsonNode id, Object result) throws IOException {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", mapper.valueToTree(result));
        writeLine(mapper.writeValueAsString(response));
    }

    private void writeErrorResponse(JsonNode id, int code, String message) throws IOException {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        ObjectNode error = mapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        response.set("error", error);
        writeLine(mapper.writeValueAsString(response));
    }

    private void writeLine(String json) throws IOException {
        synchronized (agentToClientOut) {
            agentToClientOut.write(json.getBytes(StandardCharsets.UTF_8));
            agentToClientOut.write('\n');
            agentToClientOut.flush();
        }
    }
}
