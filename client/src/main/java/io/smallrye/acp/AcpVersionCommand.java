package io.smallrye.acp;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Display the version of the acp client.
 *
 * <p>
 * Usage:
 *
 * <pre>{@code
 * acp version
 * }</pre>
 */
@CommandDefinition(name = "version", description = "Show the version of the acp client", generateHelp = true)
public class AcpVersionCommand implements Command<CommandInvocation> {

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        invocation.println(ConfigProvider.getConfig()
                .getOptionalValue("quarkus.application.version", String.class)
                .orElse("0.0.1-SNAPSHOT"));
        return CommandResult.SUCCESS;
    }
}
