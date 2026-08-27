package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Information about a command.
 */
public record AvailableCommand(
        @JsonProperty("_meta") Map<String, Object> meta,
        @JsonProperty("description") String description,
        @JsonProperty("input") Object input,
        @JsonProperty("name") String name) {
    public AvailableCommand(String description, String name) {
        this(null, description, null, name);
    }
}
