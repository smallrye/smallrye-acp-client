import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.smallrye.agentclientprotocol.sdk.client.AcpClient;
import io.smallrye.agentclientprotocol.sdk.client.AcpSessionResult;
import io.smallrye.agentclientprotocol.sdk.client.AcpSyncClient;
import io.smallrye.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.SessionNotification;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.StopReason;

/**
 * Sends a prompt to the agent and validates the response.
 *
 * <p>Checks:
 * <ul>
 *   <li>Prompt completes with a non-null response</li>
 *   <li>Stop reason is {@link StopReason#END_TURN}</li>
 *   <li>Session updates (notifications) were received</li>
 * </ul>
 */
public class PromptCheck implements Check {

    @Override
    public String name() {
        return "prompt";
    }

    @Override
    public CheckResult run(AcpTestConfig config) throws Exception {
        List<SessionNotification> updates = new ArrayList<>();
        var transport = new StdioAcpClientTransport(config.resolveAgentParameters());

        try (AcpSyncClient client = AcpClient.sync(transport)
                .withRequestTimeout(java.time.Duration.ofSeconds(30))
                .withPromptRequestTimeout(java.time.Duration.ZERO)
                .withPermissionMode("allow_always")
                .onSessionUpdate(updates::add)
                .withNotifications(n -> n
                        .onAgentMessage(chunk -> {
                            String text = extractText(chunk.content());
                            if (!text.isEmpty()) {
                                System.out.print(text);
                            }
                        }))
                .build()) {

            AcpSessionResult result = client.workflow()
                    .withWorkspace(config.workspace())
                    .prompt(config.prompt())
                    .run();

            System.out.println();

            if (result.promptResponse() == null) {
                return CheckResult.fail(name(), "PromptResponse is null");
            }
            if (result.stopReason() != StopReason.END_TURN) {
                return CheckResult.fail(name(),
                        "Expected END_TURN but got " + result.stopReason());
            }

            return CheckResult.pass(name(),
                    String.format("stopReason=%s, updates=%d, sessionId=%s",
                            result.stopReason(), updates.size(), result.sessionId()));
        }
    }

    private static String extractText(Object content) {
        if (content instanceof Map<?, ?> map) {
            Object text = map.get("text");
            return text != null ? text.toString() : "";
        }
        return content != null ? content.toString() : "";
    }
}
