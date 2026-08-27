package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request to release a terminal and free its resources.
 */
public record ReleaseTerminalRequest(
        @JsonProperty("_meta") Map<String, Object> meta,
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("terminalId") String terminalId) {
    public ReleaseTerminalRequest(String sessionId, String terminalId) {
        this(null, sessionId, terminalId);
    }
}
