package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response to `session/set_mode` method.
 */
public record SetSessionModeResponse(
        @JsonProperty("_meta") Map<String, Object> meta) {
}
