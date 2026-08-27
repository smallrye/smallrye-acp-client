package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * SSE transport configuration for MCP.
 */
public record McpServerSse(
        @JsonProperty("_meta") Map<String, Object> meta,
        @JsonProperty("headers") List<HttpHeader> headers,
        @JsonProperty("name") String name,
        @JsonProperty("url") String url) {
    public McpServerSse(List<HttpHeader> headers, String name, String url) {
        this(null, headers, name, url);
    }
}
