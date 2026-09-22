package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request parameters for listing existing sessions.
 *
 * Only available if the Agent supports the `sessionCapabilities.list` capability.
 */
public record ListSessionsRequest(
        @JsonProperty("_meta") Map<String, Object> meta,
        @JsonProperty("cursor") String cursor,
        @JsonProperty("cwd") String cwd) {
    public ListSessionsRequest() {
        this(null, null, null);
    }

    public ListSessionsRequest(String cwd) {
        this(null, null, cwd);
    }
}
