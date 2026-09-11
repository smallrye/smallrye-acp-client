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

    @Option(name = "prompt-request-timeout", description = "Timeout in seconds for prompt requests; 0 means no timeout [env: ACP_PROMPT_REQUEST_TIMEOUT]")
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

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        // Configure log level if provided
        logLevel = ProjectUtil.resolveValueWithPrecedence(logLevel, "ACP_LOG_LEVEL", null);
        if (logLevel != null && !logLevel.isEmpty()) {
            Level level = Level.parse(logLevel.toUpperCase());
            java.util.logging.Logger.getLogger("io.smallrye").setLevel(level);
            java.util.logging.Logger.getLogger("io.smallrye.acp").setLevel(level);
            java.util.logging.Logger.getLogger("io.smallrye.agentclientprotocol").setLevel(level);
            java.util.logging.Logger rootLogger = java.util.logging.Logger.getLogger("");
            if (rootLogger.getLevel().intValue() > level.intValue()) {
                rootLogger.setLevel(level);
            }
            for (var handler : rootLogger.getHandlers()) {
                if (handler.getLevel().intValue() > level.intValue()) {
                    handler.setLevel(level);
                }
            }
        }

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
        Duration pRequestTimeout = promptRequestTimeoutSecs > 0
                ? Duration.ofSeconds(promptRequestTimeoutSecs)
                : Duration.ZERO;

        // 0. Check for required env variables based on agent + provider
        checkProviderEnv(agent, provider);

        // 0b. Resolve workspace path: CLI/env > current directory
        workspacePath = ProjectUtil.resolveValueWithPrecedence(workspacePath, "WORKSPACE_PATH", null);
        String sessionCwd = workspacePath != null ? workspacePath : System.getProperty("user.dir");
        logger.infof("Workspace CWD: %s", sessionCwd);

        // 0c. Backup workspace if requested and project is Maven/Gradle
        backup = ProjectUtil.resolveValueWithPrecedence(backup, "ACP_BACKUP", "yes");
        backupProjectName = ProjectUtil.resolveValueWithPrecedence(backupProjectName, "ACP_BACKUP_PROJECT_NAME", ".");
        if ("yes".equalsIgnoreCase(backup)) {
            Path backupDir = ProjectUtil.backupWorkspace(backupProjectName, Path.of(sessionCwd));
            if (backupDir != null) {
                sessionCwd = backupDir.toAbsolutePath().toString();
                logger.infof("CWD set to backup directory: %s", sessionCwd);
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

        // 3. Build sync client with fluent API
        try (AcpSyncClient client = AcpClient.sync(transport)
                .withRequestTimeout(reqTimeout)
                .withPromptRequestTimeout(pRequestTimeout)
                .withNotifications(n -> n
                        .onAgentMessage(chunk -> {
                            flushThoughts();
                            System.out.print(extractText(chunk.content()));
                            messageOutputPending = true;
                        })
                        .onAgentThought(chunk -> thoughtBuffer.append(extractText(chunk.content())))
                        .onToolCall(tc -> {
                            flushOutput();
                            logger.infof("[ToolCall] %s (%s) - %s", tc.title(), tc.kind(), tc.status());
                        })
                        .onToolCallUpdate(tcu -> {
                            flushOutput();
                            logger.infof("[ToolUpdate] %s - %s", tcu.title(), tcu.status());
                        })
                        .onPlan(plan -> {
                            flushOutput();
                            logger.infof("[Plan] %d steps:", plan.entries().size());
                            plan.entries().forEach(e -> logger.infof("  - %s [%s]", e.content(), e.status()));
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
                                        .ifPresent(opt -> logger.infof("Model changed: %s", opt.currentValue()));
                            }
                            logger.infof("[Config] %s", config.configOptions());
                        })
                        .onCurrentMode(mode -> {
                            flushOutput();
                            logger.infof("[Mode] %s", mode.currentModeId());
                        })
                        .onUsage(usage -> {
                            flushOutput();
                            logger.infof("[Usage] used=%s size=%s cost=%s", usage.used(), usage.size(), usage.cost());
                        }))
                .withPermissionMode(permissionMode)
                .build()) {

            // 4. Execute the ACP session workflow
            AcpSessionResult result = client.workflow()
                    .initialize()
                    .onInitialized(init -> {
                        var agentInfo = init.agentInfo();
                        String title = agentInfo.title();
                        String connectedMsg = (title != null && !title.isEmpty())
                                ? String.format("Connected to the ACP agent: %s - v%s - %s",
                                        agentInfo.name(), agentInfo.version(), title)
                                : String.format("Connected to the ACP agent: %s - v%s",
                                        agentInfo.name(), agentInfo.version());
                        logger.info(connectedMsg);
                        logger.infof("Protocol version: %s", init.protocolVersion());
                        logger.debugf("Capabilities: %s", init.agentCapabilities());
                        logger.debugf("Auth methods: %s", init.authMethods());
                    })
                    .newSession(cwd)
                    .onSessionCreated(session -> {
                        logger.infof("Session created: %s with CWD: %s", session.sessionId(), cwd);
                        if (session.configOptions() != null) {
                            session.configOptions().stream()
                                    .filter(opt -> "model".equalsIgnoreCase(opt.id()))
                                    .findFirst()
                                    .ifPresent(opt -> logger.infof("Agent model: %s", opt.currentValue()));
                        }
                    })
                    .model(model)
                    .skill(skillPath)
                    .prompt(prompt)
                    .beforePrompt(() -> {
                        logger.infof("Sending prompt: %s", prompt);
                        invocation.println("Here is the AI response:");
                    })
                    .execute();

            flushOutput();
            logger.infof("Done! Stop reason: %s", result.stopReason());

            return CommandResult.SUCCESS;
        } catch (Exception e) {
            invocation.println("Error: " + e.getMessage());
            e.printStackTrace();
            return CommandResult.FAILURE;
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

    // -- Output helpers ----

    private void flushOutput() {
        flushThoughts();
        if (messageOutputPending) {
            System.out.println();
            messageOutputPending = false;
        }
    }

    private void flushThoughts() {
        if (!thoughtBuffer.isEmpty()) {
            logger.debugf("[Thought] %s", thoughtBuffer.toString().strip());
            thoughtBuffer.setLength(0);
        }
    }

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
