package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response containing the terminal output and exit status.
 */
public record TerminalOutputResponse(
        @JsonProperty("_meta") Map<String, Object> meta,
        @JsonProperty("exitStatus") TerminalExitStatus exitStatus,
        @JsonProperty("output") String output,
        @JsonProperty("truncated") Boolean truncated) {
    public TerminalOutputResponse(String output, Boolean truncated) {
        this(null, null, output, truncated);
    }
}
