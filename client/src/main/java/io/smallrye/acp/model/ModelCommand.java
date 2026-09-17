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
@CommandDefinition(name = "model", description = "Manage model: list", groupCommands = {
        ListModelsCommand.class })
public class ModelCommand implements Command<CommandInvocation> {

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        invocation.println("Query the ACP agent to get the models to be used when executing: acp run <command>");
        invocation.println("");
        invocation.println("Usage: acp model <command>");
        invocation.println("");
        invocation.println("Commands:");
        invocation.println("  list      Display the models available for an agent to interact with the LLM model");
        return CommandResult.SUCCESS;
    }
}
