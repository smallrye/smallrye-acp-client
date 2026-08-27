package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response to `terminal/kill` method
 */
public record KillTerminalResponse(
        @JsonProperty("_meta") Map<String, Object> meta) {
}
