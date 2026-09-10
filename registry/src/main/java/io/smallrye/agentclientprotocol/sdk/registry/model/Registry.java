package io.smallrye.agentclientprotocol.sdk.registry.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Registry(String version, List<Agent> agents) {
}
