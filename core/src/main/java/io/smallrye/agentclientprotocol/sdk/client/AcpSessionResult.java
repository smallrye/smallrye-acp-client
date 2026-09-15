package io.smallrye.agentclientprotocol.sdk.client;

import java.util.List;

import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.*;

/**
 * Immutable result of executing an {@link AcpSessionWorkflow}.
 *
 * <p>
 * Provides access to all intermediate responses collected during the workflow:
 * initialization, session creation, optional model configuration, and the prompt response.
 *
 * <p>
 * Example:
 *
 * <pre>{@code
 * AcpSessionResult result = client.workflow()
 *         .initialize()
 *         .newSession("/workspace")
 *         .model("claude-opus-4-6")
 *         .prompt("Say hello")
 *         .execute();
 *
 * System.out.println("Agent: " + result.agentInfo().name());
 * System.out.println("Session: " + result.sessionId());
 * System.out.println("Stop reason: " + result.stopReason());
 * }</pre>
 *
 * @see AcpSessionWorkflow
 */
public record AcpSessionResult(
        InitializeResponse initializeResponse,
        NewSessionResponse newSessionResponse,
        SetSessionConfigOptionResponse configOptionResponse,
        PromptResponse promptResponse) {

    /**
     * Returns the agent implementation info from the initialization handshake.
     */
    public Implementation agentInfo() {
        return initializeResponse != null ? initializeResponse.agentInfo() : null;
    }

    /**
     * Returns the session ID.
     */
    public String sessionId() {
        return newSessionResponse != null ? newSessionResponse.sessionId() : null;
    }

    /**
     * Returns the effective config options. If a model was set via the workflow,
     * returns the updated options from the config response; otherwise returns
     * the session's default options.
     */
    public List<SessionConfigOption> configOptions() {
        if (configOptionResponse != null && configOptionResponse.configOptions() != null) {
            return configOptionResponse.configOptions();
        }
        return newSessionResponse != null ? newSessionResponse.configOptions() : null;
    }

    /**
     * Returns the stop reason from the prompt response.
     */
    public StopReason stopReason() {
        return promptResponse != null ? promptResponse.stopReason() : null;
    }
}
