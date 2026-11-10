package io.smallrye.acp;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;

import io.smallrye.acp.mcp.McpCommand;
import io.smallrye.acp.model.ModelCommand;
import io.smallrye.acp.registry.RegistryCommand;
import io.smallrye.acp.run.RunCommand;

@CommandDefinition(name = "acp", description = "acp is an AI tool for installing an ACP Agent and interact with it: OpenCode, Claude, Pi, Gemini, IBM Bob, etc.", generateHelp = true, groupCommands = {
        RunCommand.class, RegistryCommand.class, ModelCommand.class, McpCommand.class })
public class AcpCommands implements Command<CommandInvocation> {

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        invocation.println(invocation.getHelpInfo());
        return CommandResult.SUCCESS;
    }
}
