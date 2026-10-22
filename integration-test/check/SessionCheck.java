import java.util.List;
import java.util.concurrent.TimeUnit;

import io.smallrye.agentclientprotocol.sdk.client.AcpAsyncClient;
import io.smallrye.agentclientprotocol.sdk.client.AcpClient;
import io.smallrye.agentclientprotocol.sdk.client.transport.AgentParameters;
import io.smallrye.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.CloseSessionRequest;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.NewSessionRequest;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.NewSessionResponse;

/**
 * Verifies session lifecycle: create and close.
 *
 * <p>Uses the async client to exercise each step independently:
 * initialize, create session (verify sessionId), then close session.
 */
public class SessionCheck implements Check {

    @Override
    public String name() {
        return "session-lifecycle";
    }

    @Override
    public CheckResult run(AcpTestConfig config) throws Exception {
        AgentParameters params = config.resolveAgentParameters();
        var transport = new StdioAcpClientTransport(params);
        AcpAsyncClient client = AcpClient.async(transport)
                .withRequestTimeout(java.time.Duration.ofSeconds(30))
                .build();

        try {
            client.connect().join();

            var init = client.initialize().get(15, TimeUnit.SECONDS);
            if (init == null) {
                return CheckResult.fail(name(), "Initialize failed");
            }

            NewSessionResponse session = client
                    .newSession(new NewSessionRequest(config.workspace(), List.of()))
                    .get(15, TimeUnit.SECONDS);

            if (session == null) {
                return CheckResult.fail(name(), "NewSessionResponse is null");
            }
            if (session.sessionId() == null || session.sessionId().isEmpty()) {
                return CheckResult.fail(name(), "Session ID is empty");
            }

            String sessionId = session.sessionId();

            client.closeSession(new CloseSessionRequest(sessionId))
                    .get(10, TimeUnit.SECONDS);

            return CheckResult.pass(name(),
                    String.format("created sessionId=%s, closed successfully", sessionId));
        } finally {
            transport.closeGracefully();
        }
    }
}
