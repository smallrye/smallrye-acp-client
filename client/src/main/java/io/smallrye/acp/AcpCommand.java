package io.smallrye.acp;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Option;
import org.jboss.logging.Logger;

import io.smallrye.acp.registry.RegistryCommand;
import io.smallrye.acp.toolbox.GitUtil;
import io.smallrye.acp.toolbox.ProjectUtil;
import io.smallrye.agentclientprotocol.sdk.client.AcpClient;
import io.smallrye.agentclientprotocol.sdk.client.AcpSessionResult;
import io.smallrye.agentclientprotocol.sdk.client.AcpSyncClient;
import io.smallrye.agentclientprotocol.sdk.client.transport.AgentParameters;
import io.smallrye.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import io.smallrye.agentclientprotocol.sdk.registry.AcpRegistryManager;
import io.smallrye.agentclientprotocol.sdk.registry.model.Registry;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.*;

/**
 * Aesh CLI command for any ACP-compatible agent (OpenCode, Claude, Pi, Gemini, etc.).
 *
 * <p>
 * Connects to an ACP agent over stdio, initializes a session,
 * sends a prompt, and streams session updates (thoughts, messages, tool calls, plans)
 * to the console.
 *
 * <p>
 * Each option can also be set via an environment variable (shown in brackets).
 * Precedence: CLI argument &gt; environment variable &gt; default value.
 *
 * <p>
 * Usage:
 *
 * <pre>{@code
 * # Using a known agent (resolves binary and args automatically)
 * acp --agent claude-acp --provider vertex-ai --model claude-opus-4-6 --prompt "Say hello"
 *
 * # Using a custom agent binary
 * acp --agent-binary my-agent --agent-args "serve" --prompt "Say hello"
 * }</pre>
 */
@CommandDefinition(name = "acp", description = "acp tool for any acp compatible agent (OpenCode, Claude, Pi, Gemini, etc.)", generateHelp = true, groupCommands = {
        RegistryCommand.class })
public class AcpCommand implements Command<CommandInvocation> {

    private static final Logger logger = Logger.getLogger(AcpCommand.class);

    // -- Agent resolution ----
    // Agents are resolved dynamically from the ACP registry.
    // Use 'acp install <agent-id>' to install an agent first.
    // Agent IDs match the ACP registry (e.g. opencode, claude-acp, pi-acp, gemini).

    private final AcpRegistryManager registryManager = new AcpRegistryManager();

    // -- Provider env-var requirements per agent + provider ----
    // Key format: "agent-id:provider". Checked before launching the agent.

    private static final Map<String, List<String>> PROVIDER_ENV_VARS = Map.ofEntries(
            Map.entry("opencode:zen", List.of()),
            Map.entry("opencode:vertex-ai",
                    List.of("GOOGLE_APPLICATION_CREDENTIALS", "VERTEX_LOCATION", "GOOGLE_CLOUD_PROJECT")),
            Map.entry("claude-acp:vertex-ai",
                    List.of("ANTHROPIC_VERTEX_PROJECT_ID", "CLAUDE_CODE_USE_VERTEX", "CLOUD_ML_REGION")),
            Map.entry("pi-acp:vertex-ai", List.of("GOOGLE_APPLICATION_CREDENTIALS", "GOOGLE_CLOUD_PROJECT", "CLOUD_ML_REGION")),
            Map.entry("gemini:vertex-ai", List.of("GOOGLE_CLOUD_PROJECT")));

    // -- Instance state ----

    private final StringBuilder thoughtBuffer = new StringBuilder();
    private volatile boolean messageOutputPending = false;

    // -- CLI options ----

    @Option(shortName = 'a', name = "agent", description = "ACP agent registry ID: opencode, claude-acp, pi-acp, gemini, ... (use 'acp reg list --registry' to see all) [env: ACP_AGENT]")
    String agent;

    @Option(name = "agent-binary", description = "Override agent binary path (for custom agents) [env: ACP_AGENT_BINARY]")
    String acpAgentBinary;

    @Option(name = "agent-args", description = "Override agent arguments (for custom agents) [env: ACP_AGENT_ARGS]")
    String acpAgentArgs;

    @Option(shortName = 'p', name = "prompt", description = "The prompt text to send to the agent [env: ACP_PROMPT]")
    String prompt;

    @Option(shortName = 'm', name = "model", description = "The model to use, e.g. claude-opus-4-6 (resolved per agent/provider) [env: ACP_MODEL]")
    String model;

    @Option(name = "provider", description = "Provider: zen, vertex-ai [env: ACP_PROVIDER]")
    String provider;

    @Option(name = "request-timeout", description = "Timeout in seconds for requests (initialize, create session, etc.) [env: ACP_REQUEST_TIMEOUT]")
    Integer requestTimeout;

    @Option(name = "prompt-request-timeout", description = "Timeout in seconds for prompt request; 0 means no timeout [env: ACP_PROMPT_REQUEST_TIMEOUT]")
    Integer promptRequestTimeout;

    @Option(name = "permission-mode", description = "How to respond to agent permission requests: allow_always, allow_once, reject_once, reject_always [env: ACP_PERMISSION_MODE]")
    String permissionMode;

    @Option(shortName = 'b', name = "backup", description = "Backup workspace to target/workdirs before running: yes, no (default: yes). Only applies to Maven/Gradle projects [env: ACP_BACKUP]")
    String backup;

    @Option(name = "backup-project-name", description = "Name of the project used in the backup directory: target/workdirs/<name>_<timestamp> (default: current directory name) [env: ACP_BACKUP_PROJECT_NAME]")
    String backupProjectName;

    @Option(aliases = "wks", name = "workspace-path", description = "Absolute path to the project/workspace directory used as CWD for the session. If not set, defaults to the directory where the command is executed [env: WORKSPACE_PATH]")
    String workspacePath;

    @Option(shortName = 's', name = "skill-path", description = "Absolute path to a skills folder to add as additional directory [env: SKILL_PATH]")
    String skillPath;

    @Option(shortName = 'l', name = "log-level", description = "Log level: INFO, DEBUG, TRACE, WARNING, SEVERE [env: ACP_LOG_LEVEL]")
    String logLevel;

    @Option(shortName = 'o', name = "output", description = "Output mode: default (human-friendly), json (raw JSON-RPC messages) [env: ACP_OUTPUT]")
    String output;

    @Option(shortName = 'v', name = "verbose", description = "Enable to log JSON RPC messages [env: ACP_VERBOSE]", hasValue = false)
    boolean verbose;

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        // Resolve output mode and verbose flag before configuring logging
        output = ProjectUtil.resolveValueWithPrecedence(output, "ACP_OUTPUT", "default");
        boolean useJsonOutput = "json".equalsIgnoreCase(output);
        boolean useVerbose = verbose || "true".equalsIgnoreCase(System.getenv("ACP_VERBOSE"));
        logLevel = ProjectUtil.resolveValueWithPrecedence(logLevel, "ACP_LOG_LEVEL", null);

        configureLogging(useJsonOutput, useVerbose, logLevel);

        // Resolve options: CLI arg > env var > default
        prompt = ProjectUtil.resolveValueWithPrecedence(prompt, "ACP_PROMPT", "Say Hello");
        permissionMode = ProjectUtil.resolveValueWithPrecedence(permissionMode, "ACP_PERMISSION_MODE", "allow_always");

        // -- Resolve agent binary and args ----
        agent = ProjectUtil.resolveValueWithPrecedence(agent, "ACP_AGENT", "opencode");
        acpAgentBinary = ProjectUtil.resolveValueWithPrecedence(acpAgentBinary, "ACP_AGENT_BINARY", null);
        acpAgentArgs = ProjectUtil.resolveValueWithPrecedence(acpAgentArgs, "ACP_AGENT_ARGS", null);

        String binary;
        String args;
        if (acpAgentBinary != null) {
            binary = acpAgentBinary;
            args = acpAgentArgs;
        } else {
            var agentCommand = registryManager.resolveAgentCommand(agent);
            if (agentCommand != null) {
                binary = agentCommand.binary();
                args = acpAgentArgs != null ? acpAgentArgs
                        : String.join(",", agentCommand.args());
            } else {
                Registry registry = registryManager.getCachedRegistry();
                if (registry != null && registryManager.findAgent(registry, agent) != null) {
                    invocation.println("ERROR: Agent '" + agent
                            + "' exists in the ACP registry but is not installed.");
                    invocation.println("Run:  acp reg install " + agent);
                } else {
                    invocation.println("ERROR: Unknown agent '" + agent + "'.");
                    invocation.println("Run:  acp reg list --registry   to see available agents.");
                    invocation.println("      acp reg install <id>      to install one.");
                }
                invocation.println("Alternatively, use --agent-binary to specify the agent binary directly.");
                return CommandResult.FAILURE;
            }
        }

        // -- Resolve and normalize provider ----
        provider = ProjectUtil.resolveValueWithPrecedence(provider, "ACP_PROVIDER", "zen");
        provider = normalizeProvider(provider);

        // -- Resolve model name ----
        model = ProjectUtil.resolveValueWithPrecedence(model, "ACP_MODEL", null);
        if (model != null) {
            model = resolveModelName(agent, provider, model);
        }

        // -- Timeouts ----
        String reqTimeoutStr = ProjectUtil.resolveValueWithPrecedence(
                requestTimeout != null ? requestTimeout.toString() : null,
                "ACP_REQUEST_TIMEOUT", "30");
        Duration reqTimeout = Duration.ofSeconds(Long.parseLong(reqTimeoutStr));

        String promptRequestTimeoutStr = ProjectUtil.resolveValueWithPrecedence(
                promptRequestTimeout != null ? promptRequestTimeout.toString() : null,
                "ACP_PROMPT_REQUEST_TIMEOUT", "0");
        long promptRequestTimeoutSecs = Long.parseLong(promptRequestTimeoutStr);
        Duration pRequestTimeout = promptRequestTimeoutSecs > 0 ? Duration.ofSeconds(promptRequestTimeoutSecs) : Duration.ZERO;

        // 0. Check for required env variables based on agent + provider
        checkProviderEnv(agent, provider);

        // 0b. Resolve workspace path: CLI/env > current directory
        workspacePath = ProjectUtil.resolveValueWithPrecedence(workspacePath, "WORKSPACE_PATH", null);
        String sessionCwd = workspacePath != null ? workspacePath : System.getProperty("user.dir");
        logger.debugf("Current workspace: %s", sessionCwd);

        // 0c. Backup workspace if requested and project is Maven/Gradle
        backup = ProjectUtil.resolveValueWithPrecedence(backup, "ACP_BACKUP", "yes");
        backupProjectName = ProjectUtil.resolveValueWithPrecedence(backupProjectName, "ACP_BACKUP_PROJECT_NAME", ".");
        if ("yes".equalsIgnoreCase(backup)) {
            Path backupDir = ProjectUtil.backupWorkspace(backupProjectName, Path.of(sessionCwd));
            if (backupDir != null) {
                sessionCwd = backupDir.toAbsolutePath().toString();
                logger.debugf("Workspace set to: %s", sessionCwd);
            }
        }
        final String cwd = sessionCwd;

        // 0d. Resolve skill path (URL → local path if needed)
        skillPath = ProjectUtil.resolveValueWithPrecedence(skillPath, "SKILL_PATH", null);
        if (GitUtil.isUrl(skillPath)) {
            try {
                skillPath = GitUtil.resolveFromUrl(skillPath).toString();
            } catch (IOException e) {
                invocation.println("ERROR: Failed to resolve skill from URL: " + skillPath);
                invocation.println("       " + e.getMessage());
                return CommandResult.FAILURE;
            }
        }

        // 1. Configure agent parameters
        var paramBuilder = AgentParameters.builder(binary);
        if (args != null && !args.isEmpty()) {
            for (String a : args.split(",")) {
                String trimmed = a.trim();
                if (!trimmed.isEmpty()) {
                    paramBuilder.arg(trimmed);
                }
            }
        }
        var params = paramBuilder.build();

        // 2. Create transport
        var transport = new StdioAcpClientTransport(params);

        // 3. Build sync client and configure output mode:
        //   json    — raw JSON-RPC lines to stdout (inbound + outbound), silent notification handlers
        //   verbose — human-friendly agent messages + detailed INFO-level logging of all notifications
        //   default — human-friendly agent messages only, notifications logged at DEBUG level
        var clientBuilder = AcpClient.sync(transport)
                .withRequestTimeout(reqTimeout)
                .withPromptRequestTimeout(pRequestTimeout)
                .withPermissionMode(permissionMode);

        configureOutputMode(clientBuilder, transport, useJsonOutput, useVerbose);

        try (AcpSyncClient client = clientBuilder.build()) {

            // 4. Execute the ACP session workflow
            if (!useJsonOutput) {
                invocation.println("Starting the AI conversation ...");
            }
            AcpSessionResult result = client.workflow()
                    .initialize()
                    .onInitialized(AcpCommand::logInitialized)
                    .newSession(cwd)
                    .onSessionCreated(session -> logSessionCreated(session, cwd))
                    .model(model)
                    .skill(skillPath)
                    .prompt(prompt)
                    .runSession();
            // Drain any remaining thoughts and ensure the last agent message ends with a newline
            flushOutput();
            logger.debugf("Done! Stop reason: %s", result.stopReason());

            return CommandResult.SUCCESS;
        } catch (Exception e) {
            invocation.println("Error: " + e.getMessage());
            logger.error(e);
            return CommandResult.FAILURE;
        }
    }

    // -- Notification and permission configuration ----

    /**
     * Configures the client output mode by selecting the appropriate notification handlers
     * and, for JSON mode, attaching raw message listeners to the transport.
     */
    private void configureOutputMode(AcpClient.SyncBuilder builder, StdioAcpClientTransport transport,
            boolean jsonOutput, boolean verbose) {
        if (jsonOutput) {
            transport.setRawInboundListener(System.out::println);
            transport.setRawOutboundListener(System.out::println);
            builder.withNotifications(n -> {
            });
        } else if (verbose) {
            configureVerbose(builder);
        } else {
            configureDefault(builder);
        }
    }

    /**
     * Configures human-friendly handlers: agent messages stream to stdout,
     * thoughts are buffered at DEBUG level, other notifications logged at DEBUG.
     * Permissions log title, kind, and selected option.
     */
    private void configureDefault(AcpClient.SyncBuilder builder) {
        builder.withNotifications(n -> n
                .onAgentMessage(chunk -> {
                    flushThoughts();
                    System.out.print(extractText(chunk.content()));
                    messageOutputPending = true;
                })
                .onAgentThought(chunk -> thoughtBuffer.append(extractText(chunk.content())))
                .onToolCall(tc -> {
                    flushOutput();
                    logger.debugf("[ToolCall] %s (%s) - %s", tc.title(), tc.kind(), tc.status());
                })
                .onToolCallUpdate(tcu -> {
                    flushOutput();
                    logger.debugf("[ToolUpdate] %s - %s", tcu.title(), tcu.status());
                })
                .onPlan(plan -> {
                    flushOutput();
                    logger.debugf("[Plan] %d steps:", plan.entries().size());
                    plan.entries().forEach(e -> logger.debugf("  - %s [%s]", e.content(), e.status()));
                })
                .onAvailableCommands(cmds -> {
                    flushOutput();
                    logger.debug("[Agent Commands] available:");
                    cmds.availableCommands()
                            .forEach(c -> logger.debugf("  /%s - %s", c.name(), c.description()));
                })
                .onConfigOption(config -> {
                    flushOutput();
                    if (config.configOptions() != null) {
                        config.configOptions().stream()
                                .filter(opt -> "model".equalsIgnoreCase(opt.id()))
                                .findFirst()
                                .ifPresent(opt -> logger.debugf("Model changed: %s", opt.currentValue()));
                    }
                    logger.debugf("[Config] %s", config.configOptions());
                })
                .onCurrentMode(mode -> {
                    flushOutput();
                    logger.debugf("[Mode] %s", mode.currentModeId());
                })
                .onUsage(usage -> {
                    flushOutput();
                    logger.debugf("[Usage] used=%s size=%s cost=%s", usage.used(), usage.size(), usage.cost());
                }))
                .onPermissionRequest((request, selectedOptionId) -> {
                    flushOutput();
                    logger.debugf("[Permission] %s (%s) - responded: %s",
                            request.toolCall().title(), request.toolCall().kind(), selectedOptionId);
                });
    }

    /**
     * Configures verbose handlers that log every notification and permission field at INFO.
     * Thoughts stream to stdout and are also logged; tool calls include rawInput/rawOutput.
     */
    private void configureVerbose(AcpClient.SyncBuilder builder) {
        builder.withNotifications(n -> n
                .onAgentMessage(chunk -> {
                    flushThoughts();
                    System.out.print(extractText(chunk.content()));
                    messageOutputPending = true;
                })
                .onAgentThought(chunk -> {
                    String text = extractText(chunk.content());
                    thoughtBuffer.append(text);
                    logger.infof("[Thought] %s", text);
                })
                .onUserMessage(chunk -> {
                    flushOutput();
                    logger.infof("[UserMessage] %s", extractText(chunk.content()));
                })
                .onToolCall(tc -> {
                    flushOutput();
                    logger.infof("[ToolCall] id=%s title=%s kind=%s status=%s", tc.toolCallId(), tc.title(), tc.kind(),
                            tc.status());
                    if (tc.rawInput() != null) {
                        logger.infof("[ToolCall]   rawInput: %s", tc.rawInput());
                    }
                    if (tc.content() != null && !tc.content().isEmpty()) {
                        logger.infof("[ToolCall]   content: %s", tc.content());
                    }
                })
                .onToolCallUpdate(tcu -> {
                    flushOutput();
                    logger.infof("[ToolUpdate] id=%s title=%s status=%s", tcu.toolCallId(), tcu.title(), tcu.status());
                    if (tcu.rawInput() != null) {
                        logger.infof("[ToolUpdate]   rawInput: %s", tcu.rawInput());
                    }
                    if (tcu.rawOutput() != null) {
                        logger.infof("[ToolUpdate]   rawOutput: %s", tcu.rawOutput());
                    }
                    if (tcu.content() != null && !tcu.content().isEmpty()) {
                        logger.infof("[ToolUpdate]   content: %s", tcu.content());
                    }
                })
                .onPlan(plan -> {
                    flushOutput();
                    logger.infof("[Plan] %d steps:", plan.entries().size());
                    plan.entries()
                            .forEach(e -> logger.infof("  - [%s] %s (priority=%s)", e.status(), e.content(), e.priority()));
                })
                .onAvailableCommands(cmds -> {
                    flushOutput();
                    logger.infof("[Commands] %d available:", cmds.availableCommands().size());
                    cmds.availableCommands()
                            .forEach(c -> logger.infof("  /%s - %s", c.name(), c.description()));
                })
                .onConfigOption(config -> {
                    flushOutput();
                    if (config.configOptions() != null) {
                        config.configOptions()
                                .forEach(opt -> logger.infof("[Config] %s=%s (category=%s, type=%s)", opt.id(),
                                        opt.currentValue(), opt.category(), opt.type()));
                    }
                })
                .onCurrentMode(mode -> {
                    flushOutput();
                    logger.infof("[Mode] currentModeId=%s", mode.currentModeId());
                })
                .onSessionInfo(info -> {
                    flushOutput();
                    logger.infof("[SessionInfo] title=%s updatedAt=%s", info.title(), info.updatedAt());
                })
                .onUsage(usage -> {
                    flushOutput();
                    logger.infof("[Usage] used=%s size=%s cost=%s", usage.used(), usage.size(), usage.cost());
                }))
                .onPermissionRequest((request, selectedOptionId) -> {
                    flushOutput();
                    var tc = request.toolCall();
                    logger.infof("[Permission] id=%s title=%s kind=%s", tc.toolCallId(), tc.title(), tc.kind());
                    if (tc.rawInput() != null) {
                        logger.infof("[Permission]   rawInput: %s", tc.rawInput());
                    }
                    request.options().forEach(opt -> logger.infof("[Permission]   option: %s (%s) id=%s",
                            opt.name(), opt.kind().getValue(), opt.optionId()));
                    logger.infof("[Permission]   selected: %s", selectedOptionId);
                });
    }

    // -- Logging configuration ----

    /**
     * Configures logging based on output mode, verbose flag, and explicit log level.
     *
     * <p>
     * The Quarkus console handler is always enabled ({@code quarkus.log.console.enabled=true})
     * but the default category level is {@code WARNING}, so no log messages appear unless
     * explicitly requested. This method adjusts levels at runtime based on the active flags:
     *
     * <ul>
     * <li><b>JSON output</b> — suppresses all log categories ({@code OFF}) so that stdout
     * contains only raw JSON-RPC protocol lines for machine parsing.</li>
     * <li><b>Verbose</b> — lowers {@code io.smallrye.acp} categories to {@code INFO} so
     * notification details (tool calls, thoughts, usage, permissions) reach the console.</li>
     * <li><b>Explicit level</b> — overrides the verbose default with the user-specified
     * level (e.g. {@code DEBUG}, {@code TRACE}).</li>
     * </ul>
     *
     * <p>
     * Precedence: {@code jsonOutput} wins (all output suppressed), then {@code explicitLevel},
     * then {@code verbose}.
     *
     * @param jsonOutput {@code true} to suppress all log output for JSON-RPC mode
     * @param verbose {@code true} to enable INFO-level logging for verbose mode
     * @param explicitLevel an explicit JUL level string (e.g. {@code "DEBUG"}), or {@code null}
     */
    private static void configureLogging(boolean jsonOutput, boolean verbose, String explicitLevel) {
        if (jsonOutput) {
            java.util.logging.Logger.getLogger("io.smallrye.acp").setLevel(Level.OFF);
            java.util.logging.Logger.getLogger("io.smallrye.agentclientprotocol").setLevel(Level.OFF);
            return;
        }

        Level targetLevel = null;
        if (explicitLevel != null && !explicitLevel.isEmpty()) {
            targetLevel = Level.parse(explicitLevel.toUpperCase());
        } else if (verbose) {
            targetLevel = Level.INFO;
        }

        if (targetLevel != null) {
            java.util.logging.Logger.getLogger("io.smallrye.acp").setLevel(targetLevel);
            java.util.logging.Logger.getLogger("io.smallrye.agentclientprotocol").setLevel(targetLevel);
            java.util.logging.Logger rootLogger = java.util.logging.Logger.getLogger("");
            if (rootLogger.getLevel().intValue() > targetLevel.intValue()) {
                rootLogger.setLevel(targetLevel);
            }
            for (var handler : rootLogger.getHandlers()) {
                if (handler.getLevel().intValue() > targetLevel.intValue()) {
                    handler.setLevel(targetLevel);
                }
            }
        }
    }

    // -- Provider normalization ----

    private static String normalizeProvider(String provider) {
        return switch (provider) {
            case "opencode-zen", "zen" -> "zen";
            case "vertex-ai", "google-vertex-ai",
                    "anthropic-vertex-ai" ->
                "vertex-ai";
            default -> provider;
        };
    }

    // -- Model name resolution ----

    private static String resolveModelName(String agent, String provider, String model) {
        if (model.contains("/")) {
            return model;
        }
        if ("opencode".equals(agent) && "vertex-ai".equals(provider)) {
            return "google-vertex-anthropic/" + model + "@default";
        }
        return model;
    }

    // -- Session lifecycle logging ----

    /**
     * Logs agent metadata after a successful ACP initialization handshake:
     * agent name, version, title, protocol version, capabilities, and auth methods.
     */
    private static void logInitialized(InitializeResponse init) {
        var agentInfo = init.agentInfo();
        String title = agentInfo.title();
        String connectedMsg = (title != null && !title.isEmpty())
                ? String.format("Connected to the ACP agent: %s - v%s - %s",
                        agentInfo.name(), agentInfo.version(), title)
                : String.format("Connected to the ACP agent: %s - v%s",
                        agentInfo.name(), agentInfo.version());
        logger.debugf(connectedMsg);
        logger.debugf("Protocol version: %s", init.protocolVersion());
        logger.debugf("Capabilities: %s", init.agentCapabilities());
        logger.debugf("Auth methods: %s", init.authMethods());
    }

    /**
     * Logs session creation details: session ID, working directory,
     * and the active model (if reported in the session config options).
     */
    private static void logSessionCreated(NewSessionResponse session, String cwd) {
        logger.debugf("Session created: %s with CWD: %s", session.sessionId(), cwd);
        if (session.configOptions() != null) {
            session.configOptions().stream()
                    .filter(opt -> "model".equalsIgnoreCase(opt.id()))
                    .findFirst()
                    .ifPresent(opt -> logger.debugf("Agent model: %s", opt.currentValue()));
        }
    }

    // -- Output helpers ----

    /**
     * Flushes any buffered thoughts and finalizes pending agent message output.
     *
     * <p>
     * Agent messages are streamed to stdout via {@code System.out.print()} without
     * a trailing newline (to allow incremental output). This method appends the
     * final newline when no more chunks are expected, and drains any accumulated
     * thought content to the logger. Called between notification types to ensure
     * clean output boundaries.
     */
    private void flushOutput() {
        flushThoughts();
        if (messageOutputPending) {
            System.out.println();
            messageOutputPending = false;
        }
    }

    /**
     * Drains the thought buffer to the logger at DEBUG level and resets it.
     */
    private void flushThoughts() {
        if (!thoughtBuffer.isEmpty()) {
            logger.debugf("[Thought] %s", thoughtBuffer.toString().strip());
            thoughtBuffer.setLength(0);
        }
    }

    /**
     * Extracts text from a content object. Handles both {@link Map}-based content
     * (with a {@code "text"} key) and plain objects by calling {@code toString()}.
     */
    private static String extractText(Object content) {
        if (content instanceof Map<?, ?> map) {
            Object text = map.get("text");
            return text != null ? text.toString() : content.toString();
        }
        return content != null ? content.toString() : "";
    }

    // -- Provider env-var validation ----

    private static void checkProviderEnv(String agent, String provider) {
        String key = agent + ":" + provider;
        List<String> requiredVars = PROVIDER_ENV_VARS.get(key);

        if (requiredVars == null) {
            if (!"zen".equals(provider)) {
                logger.warnf("No env var requirements defined for agent '%s' with provider '%s'", agent, provider);
            }
            return;
        }

        for (String varName : requiredVars) {
            ProjectUtil.requireEnv(varName, provider);
        }
    }
}
