package io.smallrye.acp.registry;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;

/**
 * Parent subcommand grouping ACP registry operations.
 *
 * <p>
 * Usage:
 *
 * <pre>{@code
 * acp registry list                # installed acp agents
 * acp registry list --remote       # available agents from remote acp registry
 * acp registry install opencode    # install an acp agent
 * acp registry remove opencode     # remove an installed acp agent
 * }</pre>
 */
@CommandDefinition(name = "registry", description = "Manage ACP agents via a local registry: list, install, remove", generateHelp = true, groupCommands = {
        ListAgentsCommand.class, InstallCommand.class, RemoveCommand.class })
public class RegistryCommand implements Command<CommandInvocation> {

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        invocation.println(invocation.getHelpInfo());
        return CommandResult.SUCCESS;
    }
}
