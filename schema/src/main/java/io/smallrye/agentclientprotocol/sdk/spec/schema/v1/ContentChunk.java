package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A streamed item of content
 */
public record ContentChunk(
        @JsonProperty("_meta") Map<String, Object> meta,
        @JsonProperty("content") Object content) {
    public ContentChunk(Object content) {
        this(null, content);
    }
}
