package io.smallrye.agentclientprotocol.sdk.registry.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PlatformBinary(
        String archive,
        String cmd,
        List<String> args,
        Map<String, String> env) {
}
