package io.smallrye.agentclientprotocol.sdk.registry.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Agent(
        String id,
        String name,
        String version,
        String description,
        String repository,
        String website,
        List<String> authors,
        String license,
        String icon,
        Distribution distribution) {
}
