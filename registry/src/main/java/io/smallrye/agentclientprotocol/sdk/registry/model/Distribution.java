package io.smallrye.agentclientprotocol.sdk.registry.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Distribution(
        NpxInfo npx,
        UvxInfo uvx,
        Map<String, PlatformBinary> binary) {

    public boolean hasBinary() {
        return binary != null && !binary.isEmpty();
    }

    public boolean hasNpx() {
        return npx != null && npx.packageName() != null;
    }

    public boolean hasUvx() {
        return uvx != null && uvx.packageName() != null;
    }
}
