package io.smallrye.agentclientprotocol.sdk.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jboss.logging.Logger;

import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.*;

/**
 * Fluent workflow for the common ACP session lifecycle: initialize, create session, configure model, send prompt, close
 * session.
 *
 * <p>
 * Captures all configuration via chained method calls, then executes the full workflow when {@link #runSession()} is called.
 * Returns an {@link AcpSessionResult} containing all intermediate responses.
 *
 * <p>
 * Optional lifecycle callbacks ({@link #onInitialized}, {@link #onSessionCreated}, {@link #beforePrompt}) allow real-time
 * logging or UI updates between steps.
 *
 * <p>
 * Example:
 *
 * <pre>{@code
 * AcpSessionResult result = client.workflow()
 *         .initialize()
 *         .onInitialized(init -> System.out.println("Agent: " + init.agentInfo().name()))
 *         .newSession("/workspace")
 *         .onSessionCreated(session -> System.out.println("Session: " + session.sessionId()))
 *         .model("claude-opus-4-6")
 *         .skill("/path/to/skill")
 *         .prompt("Say hello")
 *         .beforePrompt(() -> System.out.println("Waiting for response..."))
 *         .execute();
 * }</pre>
 *
 * @see AcpSyncClient#workflow()
 * @see AcpSessionResult
 */
public class AcpSessionWorkflow {

    private static final Logger logger = Logger.getLogger(AcpSessionWorkflow.class);

    private final AcpSyncClient client;
    private boolean doInitialize;
    private String cwd;
    private final List<Object> mcpServers = new ArrayList<>();
    private String model;
    private String skillPath;
    private String promptText;

    private Consumer<InitializeResponse> onInitialized;
    private Consumer<NewSessionResponse> onSessionCreated;
    private Runnable beforePrompt;

    AcpSessionWorkflow(AcpSyncClient client) {
        this.client = client;
    }

    /**
     * Marks that the ACP initialization handshake should be performed.
     */
    public AcpSessionWorkflow initialize() {
        this.doInitialize = true;
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
     * Configures the session working directory.
     *
     * @param cwd the workspace directory for the session
     */
    public AcpSessionWorkflow newSession(String cwd) {
        this.cwd = cwd;
        return this;
    }

    /**
     * Registers a callback invoked right after the session is created.
     *
     * @param callback receives the {@link NewSessionResponse}
     */
    public AcpSessionWorkflow onSessionCreated(Consumer<NewSessionResponse> callback) {
        this.onSessionCreated = callback;
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
     * Executes the workflow: initialize, create session, set model, send prompt, close session.
     *
     * <p>
     * The session is always closed when the workflow completes, even if an exception occurs during the prompt.
     *
     * @return an {@link AcpSessionResult} containing all intermediate responses
     * @throws IllegalStateException if {@link #newSession(String)} or {@link #prompt(String)} was not called
     */
    public AcpSessionResult runSession() {
        if (!doInitialize) {
            throw new IllegalStateException("initialize must be called before newSession(cwd)");
        }

        if (cwd == null) {
            throw new IllegalStateException(
                    "newSession(cwd) must be called after initialize and before prompt() and runSession()");
        }

        if (promptText == null) {
            throw new IllegalStateException("prompt(text) must be called before runSession()");
        }

        String sessionId = "";

        try {
            // 1. Initialize
            InitializeResponse initResponse = client.initialize();
            if (onInitialized != null) {
                onInitialized.accept(initResponse);
            }

            // 2. Create session
            var sessionResponse = client
                    .newSession(new NewSessionRequest(cwd, List.copyOf(mcpServers)));
            sessionId = sessionResponse.sessionId();
            if (onSessionCreated != null) {
                onSessionCreated.accept(sessionResponse);
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

            return new AcpSessionResult(initResponse, sessionResponse, configResponse, promptResponse);
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
