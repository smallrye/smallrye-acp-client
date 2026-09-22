package io.smallrye.acp.model;

import java.util.ArrayList;
import java.util.List;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandException;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Option;

import io.smallrye.acp.registry.AeshOutputHandler;
import io.smallrye.agentclientprotocol.sdk.client.AcpClient;
import io.smallrye.agentclientprotocol.sdk.client.AcpSyncClient;
import io.smallrye.agentclientprotocol.sdk.client.transport.AgentParameters;
import io.smallrye.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import io.smallrye.agentclientprotocol.sdk.registry.AcpRegistryManager;
import io.smallrye.agentclientprotocol.sdk.registry.model.InstalledAgent;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.CloseSessionRequest;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.NewSessionRequest;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.SessionConfigOption;

@CommandDefinition(name = "list", description = "Display the models available for an agent to interact with the LLM model")
public class ListModelsCommand implements Command<CommandInvocation> {

    @Option(shortName = 'a', required = true, name = "agent", description = "Acp agent name to query to get the models. Use the commands: acp registry list to get the id/name !")
    String acpAgentId;

    @Override
    public CommandResult execute(CommandInvocation invocation) throws CommandException, InterruptedException {
        try {
            listModelsAgent(invocation);
            return CommandResult.SUCCESS;
        } catch (Exception e) {
            invocation.println("Error: " + e.getMessage());
            return CommandResult.FAILURE;
        }
    }

    private void listModelsAgent(CommandInvocation invocation) throws Exception {
        invocation.println("Fetching ACP Agent models ...");

        List<LLMModel> models = new ArrayList<>();
        AcpRegistryManager acpRegistryManager = new AcpRegistryManager(new AeshOutputHandler(invocation));
        InstalledAgent acpAgentMetadata = acpRegistryManager.getInstalledAgent(acpAgentId);

        if (acpAgentMetadata == null) {
            invocation.println("ACP agent is not available under: $HOME/.acp/agents/" + acpAgentId);
            invocation.println("Verify if it exists within the acp registry: acp registry list -r | grep " + acpAgentId);
            return;
        }

        // Configure the ACP command to be executed: binary path + args
        var params = AgentParameters.builder(acpAgentMetadata.cmd())
                .args(acpAgentMetadata.args())
                .build();

        // Create the Stdio Transport to send/receive JSON RPC messages
        var transport = new StdioAcpClientTransport(params);

        // Configure the ACP client to initialize a communication and got a session with the configuration options
        // which can include models
        try (AcpSyncClient client = AcpClient.sync(transport).build()) {
            client.initialize();
            var sessionResponse = client.newSession(new NewSessionRequest(System.getProperty("user.dir"), List.of()));
            String sessionId = sessionResponse.sessionId();

            if (sessionResponse.configOptions() != null) {
                for (SessionConfigOption cfg : sessionResponse.configOptions()) {
                    if (("model".equalsIgnoreCase(String.valueOf(cfg.category()))
                            || "model".equalsIgnoreCase(cfg.id())) && cfg.options() != null) {
                        for (var opt : cfg.options()) {
                            models.add(new LLMModel(opt.name(), opt.value()));
                        }
                    }
                }
            }

            if (sessionId != null) {
                client.closeSession(new CloseSessionRequest(sessionId));
            }
        } catch (Exception e) {
            invocation.println("Failed to create an ACP session: " + e.getMessage());
            return;
        }

        if (models.isEmpty()) {
            invocation.println("No models found for agent: " + acpAgentId);
        } else {
            invocation.println("");
            invocation.println(String.format("List of LLM models available for the agent '%s':", acpAgentId));
            invocation.println("");
            invocation.println(String.format("  %-60s %s", "NAME", "VALUE TO BE USED"));
            invocation.println("  " + "-".repeat(90));
            for (LLMModel m : models) {
                invocation.println(String.format("  %-60s %s", m.name(), m.value()));
            }
        }
    }

    private record LLMModel(String name, String value) {
    }
}
