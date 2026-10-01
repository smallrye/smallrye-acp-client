import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.smallrye.agentclientprotocol.sdk.client.AcpClient;
import io.smallrye.agentclientprotocol.sdk.client.AcpSessionResult;
import io.smallrye.agentclientprotocol.sdk.client.AcpSyncClient;
import io.smallrye.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.StopReason;

/**
 * Runs a full ACP workflow (initialize -> session -> prompt -> close)
 * and validates that all steps complete successfully.
 *
 * <p>Validates:
 * <ul>
 *   <li>Agent initializes with name and protocol version</li>
 *   <li>Session is created with a valid ID</li>
 *   <li>Prompt completes with END_TURN</li>
 *   <li>Agent produces at least one message update</li>
 * </ul>
 */
public class WorkflowCheck implements Check {

    @Override
    public String name() {
        return "workflow";
    }

    @Override
    public CheckResult run(AcpTestConfig config) throws Exception {
        StringBuilder output = new StringBuilder();
        List<String> errors = new ArrayList<>();
        var transport = new StdioAcpClientTransport(config.resolveAgentParameters());

        try (AcpSyncClient client = AcpClient.sync(transport)
                .withRequestTimeout(java.time.Duration.ofSeconds(30))
                .withPromptRequestTimeout(java.time.Duration.ZERO)
                .withPermissionMode("allow_always")
                .withNotifications(n -> n
                        .onAgentMessage(chunk -> {
                            String text = extractText(chunk.content());
                            output.append(text);
                        }))
                .build()) {

            AcpSessionResult result = client.workflow()
                    .withWorkspace(config.workspace())
                    .prompt(config.prompt())
                    .run();

            if (result.initializeResponse() == null) {
                errors.add("no InitializeResponse");
            } else if (result.agentInfo() == null) {
                errors.add("no agentInfo");
            }

            if (result.sessionId() == null || result.sessionId().isEmpty()) {
                errors.add("no sessionId");
            }

            if (result.promptResponse() == null) {
                errors.add("no PromptResponse");
            } else if (result.stopReason() != StopReason.END_TURN) {
                errors.add("stopReason=" + result.stopReason() + " (expected END_TURN)");
            }

            if (output.isEmpty()) {
                errors.add("agent produced no message output");
            }

            if (!errors.isEmpty()) {
                return CheckResult.fail(name(), String.join("; ", errors));
            }

            return CheckResult.pass(name(),
                    String.format("agent=%s, sessionId=%s, outputLength=%d chars",
                            result.agentInfo().name(),
                            result.sessionId(),
                            output.length()));
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
