package io.smallrye.agentclientprotocol.sdk.registry.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InstalledAgent(
        String id,
        String name,
        String version,
        String platform,
        String distributionType,
        String cmd,
        List<String> args,
        String npxPackage,
        String uvxPackage,
        String installedAt) {
}
