package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from closing a session.
 */
public record CloseSessionResponse(
        @JsonProperty("_meta") Map<String, Object> meta) {
}
