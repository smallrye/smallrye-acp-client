package io.smallrye.agentclientprotocol.sdk.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jboss.logging.Logger;

import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.*;

/**
 * Fluent workflow for the common ACP session lifecycle: initialize, create or resume a session, configure model, send prompt,
 * close session.
 *
 * <p>
 * Captures all configuration via chained method calls, then executes the full workflow when {@link #run()} is called.
 * Initialization and session creation are handled automatically — use {@link #resumeSession(String)} to resume an existing
 * session instead.
 *
 * <p>
 * Optional lifecycle callbacks ({@link #onInitialized}, {@link #onSessionCreated}, {@link #onSessionLoaded},
 * {@link #beforePrompt}) allow real-time logging or UI updates between steps.
 *
 * <p>
 * New session example:
 *
 * <pre>{@code
 * AcpSessionResult result = client.workflow()
 *         .withWorkspace("/workspace")
 *         .onInitialized(init -> System.out.println("Agent: " + init.agentInfo().name()))
 *         .onSessionCreated(session -> System.out.println("Session: " + session.sessionId()))
 *         .model("claude-opus-4-6")
 *         .prompt("Say hello")
 *         .run();
 * }</pre>
 *
 * <p>
 * Resume session example:
 *
 * <pre>{@code
 * AcpSessionResult result = client.workflow()
 *         .withWorkspace("/workspace")
 *         .resumeSession("session-id-123")
 *         .onSessionLoaded(loaded -> System.out.println("Session resumed"))
 *         .prompt("Continue where we left off")
 *         .run();
 * }</pre>
 *
 * @see AcpSyncClient#workflow()
 * @see AcpSessionResult
 */
public class AcpSessionWorkflow {

    private static final Logger logger = Logger.getLogger(AcpSessionWorkflow.class);

    private final AcpSyncClient client;
    private String cwd;
    private String resumeSessionId;
    private final List<Object> mcpServers = new ArrayList<>();
    private String model;
    private String skillPath;
    private String promptText;

    private Consumer<InitializeResponse> onInitialized;
    private Consumer<NewSessionResponse> onSessionCreated;
    private Consumer<LoadSessionResponse> onSessionLoaded;
    private Runnable beforePrompt;

    AcpSessionWorkflow(AcpSyncClient client) {
        this.client = client;
    }

    /**
     * Sets the workspace directory for the session. If not called, defaults to {@code System.getProperty("user.dir")}.
     *
     * @param cwd the workspace directory path
     */
    public AcpSessionWorkflow withWorkspace(String cwd) {
        this.cwd = cwd;
        return this;
    }

    /**
     * Registers a callback invoked right after the initialization handshake completes.
     *
     * @param callback receives the {@link InitializeResponse}
     */
    public AcpSessionWorkflow onInitialized(Consumer<InitializeResponse> callback) {
        this.onInitialized = callback;
        return this;
    }

    /**
     * Registers a callback invoked right after a new session is created.
     *
     * @param callback receives the {@link NewSessionResponse}
     */
    public AcpSessionWorkflow onSessionCreated(Consumer<NewSessionResponse> callback) {
        this.onSessionCreated = callback;
        return this;
    }

    /**
     * Configures the workflow to resume an existing session instead of creating a new one.
     *
     * <p>
     * During {@link #run()}, the workflow will:
     * <ol>
     * <li>Call {@code session/list} to discover existing sessions</li>
     * <li>Verify the given session ID exists in the list</li>
     * <li>Call {@code session/load} to restore the session</li>
     * </ol>
     *
     * @param sessionId the ID of the session to resume
     */
    public AcpSessionWorkflow resumeSession(String sessionId) {
        this.resumeSessionId = sessionId;
        return this;
    }

    /**
     * Registers a callback invoked right after a resumed session is loaded.
     *
     * @param callback receives the {@link LoadSessionResponse}
     */
    public AcpSessionWorkflow onSessionLoaded(Consumer<LoadSessionResponse> callback) {
        this.onSessionLoaded = callback;
        return this;
    }

    /**
     * Adds an MCP server that the agent should connect to during the session. Accepts any of the transport-specific types:
     * {@link McpServerStdio}, {@link McpServerSse}, or {@link McpServerHttp}.
     *
     * @param mcpServer the MCP server configuration
     */
    public AcpSessionWorkflow mcpServer(Object mcpServer) {
        this.mcpServers.add(mcpServer);
        return this;
    }

    /**
     * Adds multiple MCP servers that the agent should connect to during the session.
     *
     * @param mcpServers the MCP server configurations
     */
    public AcpSessionWorkflow mcpServers(List<?> mcpServers) {
        this.mcpServers.addAll(mcpServers);
        return this;
    }

    /**
     * Configures the model to use for the session. If the agent does not support {@code session/set_config_option}, this is
     * silently skipped.
     *
     * @param model the model identifier (e.g. {@code "claude-opus-4-6"}), or {@code null} to skip
     */
    public AcpSessionWorkflow model(String model) {
        this.model = model;
        return this;
    }

    /**
     * Configures a skill path whose instructions are appended to the prompt.
     *
     * @param skillPath path to the skill directory, or {@code null} to skip
     */
    public AcpSessionWorkflow skill(String skillPath) {
        this.skillPath = skillPath;
        return this;
    }

    /**
     * Sets the prompt text to send to the agent.
     *
     * @param prompt the user prompt
     */
    public AcpSessionWorkflow prompt(String prompt) {
        this.promptText = prompt;
        return this;
    }

    /**
     * Registers a callback invoked right before the prompt is sent.
     *
     * @param callback the pre-prompt hook
     */
    public AcpSessionWorkflow beforePrompt(Runnable callback) {
        this.beforePrompt = callback;
        return this;
    }

    /**
     * Executes the workflow: initialize, create or resume session, set model, send prompt, close session.
     *
     * <p>
     * If {@link #resumeSession(String)} was called, the session is loaded from the agent's session list
     * instead of creating a new one. Otherwise a new session is created.
     *
     * <p>
     * The session is always closed when the workflow completes, even if an exception occurs during the prompt.
     *
     * @return an {@link AcpSessionResult} containing all intermediate responses
     * @throws IllegalStateException if {@link #prompt(String)} was not called
     */
    public AcpSessionResult run() {
        if (promptText == null) {
            throw new IllegalStateException("prompt(text) must be called before run()");
        }

        String effectiveCwd = cwd != null ? cwd : System.getProperty("user.dir");
        String sessionId = "";

        try {
            // 1. Initialize
            InitializeResponse initResponse = client.initialize();
            if (onInitialized != null) {
                onInitialized.accept(initResponse);
            }

            NewSessionResponse sessionResponse = null;
            LoadSessionResponse loadResponse = null;

            if (resumeSessionId != null) {
                // Resume flow: list sessions → find match → load session
                var listResponse = client.listSessions(new ListSessionsRequest());
                var sessions = listResponse.sessions();
                if (sessions == null || sessions.isEmpty()) {
                    throw new IllegalStateException("No sessions available to resume");
                }
                var matchingSession = sessions.stream()
                        .filter(s -> resumeSessionId.equals(s.sessionId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "Session '" + resumeSessionId + "' not found in agent's session list"));

                logger.debugf("Found session to resume: %s (cwd=%s, title=%s, updatedAt=%s)",
                        matchingSession.sessionId(), matchingSession.cwd(),
                        matchingSession.title(), matchingSession.updatedAt());

                String loadCwd = matchingSession.cwd() != null ? matchingSession.cwd() : effectiveCwd;
                loadResponse = client.loadSession(
                        new LoadSessionRequest(loadCwd, List.copyOf(mcpServers), resumeSessionId));
                sessionId = resumeSessionId;

                if (onSessionLoaded != null) {
                    onSessionLoaded.accept(loadResponse);
                }
            } else {
                // New session flow
                sessionResponse = client.newSession(new NewSessionRequest(effectiveCwd, List.copyOf(mcpServers)));
                sessionId = sessionResponse.sessionId();
                if (onSessionCreated != null) {
                    onSessionCreated.accept(sessionResponse);
                }
            }

            // 3. Set model (optional)
            SetSessionConfigOptionResponse configResponse = null;
            if (model != null && !model.isEmpty()) {
                try {
                    configResponse = client.setConfigOption(
                            new SetSessionConfigOptionRequest("model", sessionId, model));
                } catch (RuntimeException e) {
                    if (e.getMessage() != null && e.getMessage().contains("-32601")) {
                        logger.warnf("Agent does not support session/set_config_option -- skipping model configuration.");
                    } else {
                        throw e;
                    }
                }
            }

            // 4. Build effective prompt (append skill instruction if set)
            String effectivePrompt = promptText;
            if (skillPath != null && !skillPath.isEmpty()) {
                effectivePrompt = promptText + "\n\nPlease read the skill: " + skillPath
                        + " and follow its instructions.";
            }

            // 5. Send prompt
            if (beforePrompt != null) {
                beforePrompt.run();
            }
            var promptResponse = client.prompt(new PromptRequest(
                    List.of(new TextContent(effectivePrompt)), sessionId));

            return new AcpSessionResult(initResponse, sessionResponse, configResponse, promptResponse, loadResponse,
                    resumeSessionId);
        } catch (Exception e) {
            logger.errorf("Failed to run the session: %s", e.getMessage());
            throw new RuntimeException(e);
        } finally {
            // 6. Close session
            if (sessionId != null) {
                try {
                    client.closeSession(new CloseSessionRequest(sessionId));
                } catch (Exception e) {
                    logger.warnf("Failed to close session %s: %s", sessionId, e.getMessage());
                }
            }
        }
    }
}
