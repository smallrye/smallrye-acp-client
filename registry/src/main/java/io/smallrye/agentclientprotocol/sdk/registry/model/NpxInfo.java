package io.smallrye.agentclientprotocol.sdk.registry.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NpxInfo(
        @JsonProperty("package") String packageName,
        List<String> args,
        Map<String, String> env) {
}
