package io.smallrye.agentclientprotocol.sdk.client.transport;

import java.util.function.Consumer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.TextContent;

/**
 * Transport abstraction for ACP client-to-agent communication.
 *
 * <p>
 * Implementations exchange newline-delimited JSON-RPC 2.0 messages
 * between the client and an agent endpoint. The default implementation
 * ({@link StdioAcpClientTransport}) communicates via subprocess stdin/stdout;
 * test implementations may use pipes or other mechanisms.
 *
 * @see StdioAcpClientTransport
 */
public interface AcpTransport {

    /**
     * Registers the handler that receives inbound JSON-RPC messages
     * (agent to client). Must be called before {@link #connect()}.
     *
     * @param handler the message consumer
     */
    void setInboundMessageHandler(Consumer<JsonNode> handler);

    /**
     * Opens the transport. After this returns, inbound messages will
     * be delivered to the handler and {@link #sendMessage} will accept
     * outbound messages.
     */
    void connect();

    /**
     * Enqueues a JSON-RPC message for delivery to the agent.
     *
     * @param message the JSON message to send
     */
    void sendMessage(JsonNode message);

    /**
     * Shuts down the transport, releasing all resources.
     */
    void closeGracefully();

    /**
     * Returns the {@link ObjectMapper} used for JSON serialization/deserialization.
     * The mapper is preconfigured with ACP-specific settings.
     *
     * @return the configured mapper
     */
    ObjectMapper getMapper();

    ObjectMapper DEFAULT_MAPPER = initDefaultMapper();

    private static ObjectMapper initDefaultMapper() {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);

        SimpleModule module = new SimpleModule("AcpContentTypes");
        module.addSerializer(TextContent.class, new TextContentSerializer());
        mapper.registerModule(module);
        return mapper;
    }

    /**
     * Returns the shared default {@link ObjectMapper} with ACP-specific configuration:
     * <ul>
     * <li>{@code FAIL_ON_UNKNOWN_PROPERTIES = false} for forward compatibility</li>
     * <li>{@code NON_NULL} serialization to avoid sending null fields</li>
     * <li>A custom {@link TextContent} serializer that adds the required
     * {@code "type": "text"} discriminator</li>
     * </ul>
     *
     * @return the shared configured mapper
     */
    static ObjectMapper createDefaultMapper() {
        return DEFAULT_MAPPER;
    }
}
