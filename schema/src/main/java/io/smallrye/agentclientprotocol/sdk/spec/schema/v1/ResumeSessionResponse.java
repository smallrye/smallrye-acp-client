package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from resuming an existing session.
 */
public record ResumeSessionResponse(
        @JsonProperty("_meta") Map<String, Object> meta,
        @JsonProperty("configOptions") List<SessionConfigOption> configOptions,
        @JsonProperty("modes") SessionModeState modes) {
}
