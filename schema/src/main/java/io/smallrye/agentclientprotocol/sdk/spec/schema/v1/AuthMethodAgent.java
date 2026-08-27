package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Agent handles authentication itself.
 *
 * This is the default authentication method type.
 */
public record AuthMethodAgent(
        @JsonProperty("_meta") Map<String, Object> meta,
        @JsonProperty("description") String description,
        @JsonProperty("id") String id,
        @JsonProperty("name") String name) {
    public AuthMethodAgent(String id, String name) {
        this(null, null, id, name);
    }
}
