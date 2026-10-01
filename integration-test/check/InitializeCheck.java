import io.smallrye.agentclientprotocol.sdk.client.AcpSyncClient;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.InitializeResponse;

/**
 * Verifies the ACP initialize handshake.
 *
 * <p>Checks that the agent responds with a valid protocol version
 * and agent implementation info (name, version).
 */
public class InitializeCheck implements Check {

    @Override
    public String name() {
        return "initialize";
    }

    @Override
    public CheckResult run(AcpTestConfig config) throws Exception {
        try (AcpSyncClient client = config.createClient()) {
            var result = client.workflow()
                    .withWorkspace(config.workspace())
                    .prompt(config.prompt())
                    .run();

            InitializeResponse init = result.initializeResponse();

            if (init == null) {
                return CheckResult.fail(name(), "InitializeResponse is null");
            }
            if (init.protocolVersion() == null) {
                return CheckResult.fail(name(), "Protocol version is null");
            }
            if (init.agentInfo() == null) {
                return CheckResult.fail(name(), "Agent info is null");
            }
            if (init.agentInfo().name() == null || init.agentInfo().name().isEmpty()) {
                return CheckResult.fail(name(), "Agent name is empty");
            }

            return CheckResult.pass(name(),
                    String.format("agent=%s v%s, protocol=%d",
                            init.agentInfo().name(),
                            init.agentInfo().version(),
                            init.protocolVersion()));
        }
    }
}
