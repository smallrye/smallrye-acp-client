package io.smallrye.acp.registry;

import org.aesh.command.invocation.CommandInvocation;

import io.smallrye.agentclientprotocol.sdk.registry.OutputHandler;

public class AeshOutputHandler implements OutputHandler {

    private final CommandInvocation invocation;

    public AeshOutputHandler(CommandInvocation invocation) {
        this.invocation = invocation;
    }

    @Override
    public void info(String message) {
        invocation.println(message);
    }

    @Override
    public void error(String message) {
        invocation.println(message);
    }
}
