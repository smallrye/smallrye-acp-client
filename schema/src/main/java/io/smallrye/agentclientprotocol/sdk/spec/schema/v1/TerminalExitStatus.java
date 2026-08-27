package io.smallrye.agentclientprotocol.sdk.spec.schema.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Exit status of a terminal command.
 */
public record TerminalExitStatus(
        @JsonProperty("_meta") Map<String, Object> meta,
        @JsonProperty("exitCode") Integer exitCode,
        @JsonProperty("signal") String signal) {
}
