package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request parameters for the authenticate method.
 *
 * Specifies which authentication method to use.
 */
public record AuthenticateRequest(
        @JsonProperty("_meta") Map<String, Object> meta,
        @JsonProperty("methodId") String methodId) {
    public AuthenticateRequest(String methodId) {
        this(null, methodId);
    }
}
