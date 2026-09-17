package io.smallrye.acp.registry;

import java.nio.file.Path;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import io.smallrye.agentclientprotocol.sdk.registry.AcpRegistryManager;

/**
 * Subcommand that installs an ACP agent from the remote registry or a local registry file.
 *
 * <p>
 * Usage:
 *
 * <pre>{@code
 * acp registry install opencode
 * acp registry install claude-acp --force
 * acp registry install bob-acp --registry-file /path/to/custom-registry.json
 * }</pre>
 *
 * <p>
 * The agent binary (or npx/uvx metadata) is stored under
 * {@code $HOME/.acp/agents/<agent-id>/}.
 */
@CommandDefinition(name = "install", description = "Install an ACP agent from the registry")
public class InstallCommand implements Command<CommandInvocation> {

    @Argument(description = "Agent identifier from the ACP registry (e.g. opencode, claude-acp, gemini)", required = true)
    String agentId;

    @Option(shortName = 'f', name = "force", hasValue = false, description = "Force reinstall even if the agent is already installed")
    boolean force;

    @Option(name = "registry-file", description = "Path to a local registry JSON file (bypasses the remote registry fetch)")
    String registryFile;

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        try {
            var manager = new AcpRegistryManager(new AeshOutputHandler(invocation));
            if (registryFile != null) {
                var registry = manager.loadRegistryFromFile(Path.of(registryFile));
                manager.installAgent(agentId, force, registry);
            } else {
                manager.installAgent(agentId, force);
            }
            return CommandResult.SUCCESS;
        } catch (Exception e) {
            invocation.println("Error installing agent: " + e.getMessage());
            return CommandResult.FAILURE;
        }
    }
}
