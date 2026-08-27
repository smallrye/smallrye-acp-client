package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response to `fs/write_text_file`
 */
public record WriteTextFileResponse(
        @JsonProperty("_meta") Map<String, Object> meta) {
}
