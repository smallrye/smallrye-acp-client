package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * MCP capabilities supported by the agent
 */
public record McpCapabilities(
        @JsonProperty("_meta") Map<String, Object> meta,
        @JsonProperty("http") Boolean http,
        @JsonProperty("sse") Boolean sse) {
}
