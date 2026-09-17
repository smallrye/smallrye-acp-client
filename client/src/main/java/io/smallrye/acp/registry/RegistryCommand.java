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
@CommandDefinition(name = "registry", description = "Manage ACP agents via the registry: list, install, remove", groupCommands = {
        ListAgentsCommand.class, InstallCommand.class, RemoveCommand.class })
public class RegistryCommand implements Command<CommandInvocation> {

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        invocation.println(
                "Manage ACP-compatible agents locally using the https://agentclientprotocol.com/get-started/registry.");
        invocation.println("");
        invocation.println("Usage: acp registry <command>");
        invocation.println("");
        invocation.println("Commands:");
        invocation.println(
                "  list      List ACP agents: installed or available from the registry");
        invocation.println("  install   Install an ACP agent from the remote registry under $HOME/.acp/agents");
        invocation.println("  remove    Remove an installed ACP agent");
        return CommandResult.SUCCESS;
    }
}
