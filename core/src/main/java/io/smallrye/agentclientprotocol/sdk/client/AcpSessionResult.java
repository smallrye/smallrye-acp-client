package io.smallrye.agentclientprotocol.sdk.client;

import java.util.List;

import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.*;

/**
 * Immutable result of executing an {@link AcpSessionWorkflow}.
 *
 * <p>
 * Provides access to all intermediate responses collected during the workflow:
 * initialization, session creation (or session loading for resumed sessions),
 * optional model configuration, and the prompt response.
 *
 * <p>
 * Example (new session):
 *
 * <pre>{@code
 * AcpSessionResult result = client.workflow()
 *         .withWorkspace("/workspace")
 *         .model("claude-opus-4-6")
 *         .prompt("Say hello")
 *         .run();
 *
 * System.out.println("Agent: " + result.agentInfo().name());
 * System.out.println("Session: " + result.sessionId());
 * System.out.println("Stop reason: " + result.stopReason());
 * }</pre>
 *
 * <p>
 * Example (resumed session):
 *
 * <pre>{@code
 * AcpSessionResult result = client.workflow()
 *         .withWorkspace("/workspace")
 *         .resumeSession("session-id-123")
 *         .prompt("Continue where we left off")
 *         .run();
 * }</pre>
 *
 * @see AcpSessionWorkflow
 */
public record AcpSessionResult(
        InitializeResponse initializeResponse,
        NewSessionResponse newSessionResponse,
        SetSessionConfigOptionResponse configOptionResponse,
        PromptResponse promptResponse,
        LoadSessionResponse loadSessionResponse) {

    /**
     * Creates a result for a new session workflow (no resume).
     */
    public AcpSessionResult(
            InitializeResponse initializeResponse,
            NewSessionResponse newSessionResponse,
            SetSessionConfigOptionResponse configOptionResponse,
            PromptResponse promptResponse) {
        this(initializeResponse, newSessionResponse, configOptionResponse, promptResponse, null);
    }

    /**
     * Returns the agent implementation info from the initialization handshake.
     */
    public Implementation agentInfo() {
        return initializeResponse != null ? initializeResponse.agentInfo() : null;
    }

    /**
     * Returns the session ID. For new sessions this comes from the session creation response;
     * for resumed sessions it is not available here (the caller already knows it).
     */
    public String sessionId() {
        return newSessionResponse != null ? newSessionResponse.sessionId() : null;
    }

    /**
     * Returns {@code true} if this result represents a resumed session.
     */
    public boolean isResumedSession() {
        return loadSessionResponse != null;
    }

    /**
     * Returns the effective config options. Checks (in order): explicit config option response,
     * loaded session response, new session response.
     */
    public List<SessionConfigOption> configOptions() {
        if (configOptionResponse != null && configOptionResponse.configOptions() != null) {
            return configOptionResponse.configOptions();
        }
        if (loadSessionResponse != null && loadSessionResponse.configOptions() != null) {
            return loadSessionResponse.configOptions();
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