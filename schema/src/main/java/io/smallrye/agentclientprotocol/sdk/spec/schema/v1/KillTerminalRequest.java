package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request to kill a terminal without releasing it.
 */
public record KillTerminalRequest(
        @JsonProperty("_meta") Map<String, Object> meta,
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("terminalId") String terminalId) {
    public KillTerminalRequest(String sessionId, String terminalId) {
        this(null, sessionId, terminalId);
    }
}
