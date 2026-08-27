package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from processing a user prompt.
 *
 * See protocol docs: [Check for Completion](https://agentclientprotocol.com/protocol/prompt-turn#4-check-for-completion)
 */
public record PromptResponse(
        @JsonProperty("_meta") Map<String, Object> meta,
        @JsonProperty("stopReason") StopReason stopReason,
        @JsonProperty("usage") Map<String, Object> usage) {
    public PromptResponse(StopReason stopReason) {
        this(null, stopReason, null);
    }
}
