package io.smallrye.agentclientprotocol.sdk.registry.model;

import java.util.List;

public record AgentCommand(String binary, List<String> args) {
}
