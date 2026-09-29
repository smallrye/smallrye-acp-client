import java.time.Duration;
import java.util.List;

import io.smallrye.agentclientprotocol.sdk.client.AcpClient;
import io.smallrye.agentclientprotocol.sdk.client.AcpSyncClient;
import io.smallrye.agentclientprotocol.sdk.client.transport.AgentParameters;
import io.smallrye.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import io.smallrye.agentclientprotocol.sdk.registry.AcpRegistryManager;
import io.smallrye.agentclientprotocol.sdk.registry.model.AgentCommand;

/**
 * Configures the ACP agent for integration testing.
 *
 * <p>Resolves the agent binary either from the ACP registry ({@code ~/.acp/agents/})
 * or from explicit binary path and arguments.
 */
public class AcpTestConfig {

    private String agentId;
    private String agentBinary;
    private String agentArgs;
    private String prompt = "Say Hello";
    private String workspace;
    private Duration requestTimeout = Duration.ofSeconds(30);
    private Duration promptTimeout = Duration.ZERO;
    private String permissionMode = "allow_always";

    private AcpTestConfig() {
    }

    public static AcpTestConfig fromArgs(String[] args) {
        AcpTestConfig config = new AcpTestConfig();
        config.workspace = System.getProperty("user.dir");

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--agent", "-a" -> config.agentId = args[++i];
                case "--agent-binary" -> config.agentBinary = args[++i];
                case "--agent-args" -> config.agentArgs = args[++i];
                case "--prompt", "-p" -> config.prompt = args[++i];
                case "--workspace", "-w" -> config.workspace = args[++i];
                case "--request-timeout" -> config.requestTimeout = Duration.ofSeconds(Long.parseLong(args[++i]));
                case "--prompt-timeout" -> config.promptTimeout = Duration.ofSeconds(Long.parseLong(args[++i]));
                case "--permission-mode" -> config.permissionMode = args[++i];
            }
        }

        if (config.agentId == null && config.agentBinary == null) {
            config.agentId = envOrDefault("ACP_AGENT", "bob");
        }
        if (config.prompt.equals("Say Hello")) {
            config.prompt = envOrDefault("ACP_PROMPT", config.prompt);
        }

        return config;
    }

    public AgentParameters resolveAgentParameters() {
        String binary;
        List<String> args;

        if (agentBinary != null) {
            binary = agentBinary;
            args = agentArgs != null ? List.of(agentArgs.split(",")) : List.of();
        } else {
            AcpRegistryManager registry = new AcpRegistryManager();
            AgentCommand command = registry.resolveAgentCommand(agentId);
            if (command == null) {
                throw new IllegalStateException(
                        "Agent '" + agentId + "' is not installed. Run: acp registry install " + agentId);
            }
            binary = command.binary();
            args = command.args();
            System.out.printf("  Resolved agent '%s' -> %s %s%n", agentId, binary, args);
        }

        var builder = AgentParameters.builder(binary);
        if (!args.isEmpty()) {
            builder.args(args);
        }
        return builder.build();
    }

    public StdioAcpClientTransport createTransport() {
        return new StdioAcpClientTransport(resolveAgentParameters());
    }

    public AcpSyncClient createClient() {
        return AcpClient.sync(createTransport())
                .withRequestTimeout(requestTimeout)
                .withPromptRequestTimeout(promptTimeout)
                .withPermissionMode(permissionMode)
                .build();
    }

    public String prompt() {
        return prompt;
    }

    public String workspace() {
        return workspace;
    }

    public String agentId() {
        return agentId != null ? agentId : agentBinary;
    }

    private static String envOrDefault(String envVar, String defaultValue) {
        String val = System.getenv(envVar);
        return val != null && !val.isEmpty() ? val : defaultValue;
    }
}
