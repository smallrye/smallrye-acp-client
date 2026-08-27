package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A possible value for a session configuration option.
 */
public record SessionConfigSelectOption(
        @JsonProperty("_meta") Map<String, Object> meta,
        @JsonProperty("description") String description,
        @JsonProperty("name") String name,
        @JsonProperty("value") String value) {
    public SessionConfigSelectOption(String name, String value) {
        this(null, null, name, value);
    }
}
