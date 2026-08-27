package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request parameters for creating a new session.
 *
 * See protocol docs: [Creating a Session](https://agentclientprotocol.com/protocol/session-setup#creating-a-session)
 */
public record NewSessionRequest(
        @JsonProperty("_meta") Map<String, Object> meta,
        @JsonProperty("cwd") String cwd,
        @JsonProperty("mcpServers") List<Object> mcpServers) {
    public NewSessionRequest(String cwd, List<Object> mcpServers) {
        this(null, cwd, mcpServers);
    }
}
