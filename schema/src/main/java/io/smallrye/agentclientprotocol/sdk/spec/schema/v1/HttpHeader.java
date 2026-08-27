package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An HTTP header to set when making requests to the MCP server.
 */
public record HttpHeader(
        @JsonProperty("_meta") Map<String, Object> meta,
        @JsonProperty("name") String name,
        @JsonProperty("value") String value) {
    public HttpHeader(String name, String value) {
        this(null, name, value);
    }
}
