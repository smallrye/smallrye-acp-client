package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response to the `authenticate` method.
 */
public record AuthenticateResponse(
        @JsonProperty("_meta") Map<String, Object> meta) {
}
