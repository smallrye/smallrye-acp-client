package io.smallrye.agentclientprotocol.sdk.registry.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UvxInfo(
        @JsonProperty("package") String packageName,
        List<String> args) {
}
