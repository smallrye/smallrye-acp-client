package io.smallrye.acp.model;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;

/**
 * Parent subcommand grouping ACP model operations.
 *
 * <p>
 * Usage:
 *
 * <pre>{@code
 * acp model list # List the model(s) that an acp agent can use
 * }</pre>
 */
@CommandDefinition(name = "model", description = "Manage model: list", generateHelp = true, groupCommands = {
        ListModelsCommand.class })
public class ModelCommand implements Command<CommandInvocation> {

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        invocation.println(invocation.getHelpInfo());
        return CommandResult.SUCCESS;
    }
}
