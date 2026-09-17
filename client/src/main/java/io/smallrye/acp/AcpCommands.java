package io.smallrye.acp;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;

import io.smallrye.acp.model.ModelCommand;
import io.smallrye.acp.registry.RegistryCommand;
import io.smallrye.acp.run.RunCommand;

@CommandDefinition(name = "acp", description = "acp is an AI tool for installing an ACP Agent and interact with it: OpenCode, Claude, Pi, Gemini, IBM Bob, etc.", generateHelp = true, groupCommands = {
        RunCommand.class, RegistryCommand.class, ModelCommand.class })
public class AcpCommands implements Command<CommandInvocation> {

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        invocation.println(
                "acp is an AI tool for installing an ACP Agent and interact with it");
        invocation.println("");
        invocation.println("For more information about ACP: https://agentclientprotocol.com/get-started/introduction");
        invocation.println("");
        invocation.println("Usage: acp <command>");
        invocation.println("");
        invocation.println("Commands:");
        invocation.println("  run        Run an ACP agent conversation in headless mode");
        invocation.println("  registry   Manage ACP agents via a local registry: list, install, remove");
        invocation.println("  model      Display the models available for an agent to interact with the LLM model");
        return CommandResult.SUCCESS;
    }
}
