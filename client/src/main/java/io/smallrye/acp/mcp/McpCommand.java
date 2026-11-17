package io.smallrye.acp.mcp;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;

/**
 * Parent subcommand grouping the MCP server registry operations.
 *
 * <p>
 * Usage:
 *
 * <pre>{@code
 * acp mcp add quarkit -- jbang mcp@quarkit                                  # stdio: started by the agent
 * acp mcp add -t http quarkit-http http://localhost:8080/mcp -- jbang mcp@quarkit   # http + how to serve it
 * acp mcp add-config-file ~/.gemini/settings.json                           # import servers from a file
 * acp mcp list --check
 * acp mcp get quarkit --check --verbose
 * acp mcp serve quarkit-http
 * acp mcp tools quarkit --schema
 * acp mcp call quarkit quarkit_reference_lookup '{"map":"annotation","key":"@Transactional"}'
 * acp mcp remove quarkit
 * acp run -a claude-acp --mcp-server quarkit -p "..."
 * }</pre>
 */
@CommandDefinition(name = "mcp", generateHelp = true, description = "Manage the MCP servers that acp passes to the agents: add, add-config-file, list, get, remove, serve, tools, call", groupCommands = {
        McpAddCommand.class, McpAddConfigFileCommand.class, McpListCommand.class, McpGetCommand.class,
        McpRemoveCommand.class, McpServeCommand.class, McpToolsCommand.class, McpCallCommand.class })
public class McpCommand implements Command<CommandInvocation> {

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        invocation.println(invocation.getHelpInfo());
        return CommandResult.SUCCESS;
    }
}
