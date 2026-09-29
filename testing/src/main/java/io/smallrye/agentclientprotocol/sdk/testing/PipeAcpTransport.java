package io.smallrye.agentclientprotocol.sdk.testing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.agentclientprotocol.sdk.client.transport.AcpTransport;

/**
 * Pipe-based {@link AcpTransport} for testing.
 *
 * <p>
 * Reads and writes newline-delimited JSON-RPC 2.0 messages over
 * {@link InputStream}/{@link OutputStream} pairs instead of a subprocess.
 * Same threading model as {@code StdioAcpClientTransport}: one reader thread
 * for inbound messages, one writer thread draining an outbound queue.
 *
 * <p>
 * Not intended for direct use — obtain a pre-wired instance via
 * {@link MockAcpAgent#transport()}.
 *
 * @see MockAcpAgent
 */
public class PipeAcpTransport implements AcpTransport {

    private static final Logger logger = Logger.getLogger(PipeAcpTransport.class);

    private final InputStream inbound;
    private final OutputStream outbound;
    private final ObjectMapper mapper;

    private volatile boolean isClosing = false;
    private final LinkedBlockingQueue<JsonNode> outboundQueue = new LinkedBlockingQueue<>();

    private final ExecutorService inboundExecutor;
    private final ExecutorService outboundExecutor;

    private Consumer<JsonNode> inboundMessageHandler;

    /**
     * Creates a pipe transport with the default ACP-configured mapper.
     *
     * @param inbound the stream from which agent responses are read
     * @param outbound the stream to which client requests are written
     */
    public PipeAcpTransport(InputStream inbound, OutputStream outbound) {
        this(inbound, outbound, AcpTransport.createDefaultMapper());
    }

    /**
     * Creates a pipe transport with a custom mapper.
     *
     * @param inbound the stream from which agent responses are read
     * @param outbound the stream to which client requests are written
     * @param mapper the Jackson mapper for JSON processing
     */
    public PipeAcpTransport(InputStream inbound, OutputStream outbound, ObjectMapper mapper) {
        this.inbound = inbound;
        this.outbound = outbound;
        this.mapper = mapper;

        this.inboundExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "acp-pipe-inbound");
            t.setDaemon(true);
            return t;
        });
        this.outboundExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "acp-pipe-outbound");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void setInboundMessageHandler(Consumer<JsonNode> handler) {
        this.inboundMessageHandler = handler;
    }

    @Override
    public void connect() {
        inboundExecutor.execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inbound))) {
                String line;
                while (!isClosing && (line = reader.readLine()) != null) {
                    try {
                        JsonNode message = mapper.readTree(line);
                        if (inboundMessageHandler != null) {
                            inboundMessageHandler.accept(message);
                        }
                    } catch (Exception e) {
                        if (!isClosing) {
                            logger.errorf(e, "Error processing inbound pipe message: %s", line);
                        }
                        break;
                    }
                }
            } catch (IOException e) {
                if (!isClosing) {
                    logger.error("Error reading from inbound pipe", e);
                }
            }
        });

        outboundExecutor.execute(() -> {
            try {
                while (!isClosing) {
                    JsonNode message = outboundQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (message != null && !isClosing) {
                        String json = mapper.writeValueAsString(message);
                        synchronized (outbound) {
                            outbound.write(json.getBytes(StandardCharsets.UTF_8));
                            outbound.write('\n');
                            outbound.flush();
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                if (!isClosing) {
                    logger.error("Error writing to outbound pipe", e);
                }
            }
        });
    }

    @Override
    public void sendMessage(JsonNode message) {
        outboundQueue.add(message);
    }

    @Override
    public void closeGracefully() {
        isClosing = true;
        try {
            inbound.close();
        } catch (IOException ignored) {
        }
        try {
            outbound.close();
        } catch (IOException ignored) {
        }
        inboundExecutor.shutdownNow();
        outboundExecutor.shutdownNow();
        try {
            inboundExecutor.awaitTermination(2, TimeUnit.SECONDS);
            outboundExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public ObjectMapper getMapper() {
        return mapper;
    }
}
