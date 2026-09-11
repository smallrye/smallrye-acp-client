# Java Library for Agent Client Protocol (ACP)

The [Agent Client Protocol](https://agentclientprotocol.com/) (ACP) is an open standard for communication between clients and AI coding agents. It defines a JSON-RPC 2.0-based protocol over stdio that lets clients initialize sessions, send prompts, receive streamed updates (thoughts, messages, tool calls, plans), and manage the agent lifecycle.

This project is a Java library for ACP, built with standard `java.util.concurrent` APIs (`CompletableFuture`, `ScheduledExecutorService`) for async operations and [Jackson](https://github.com/FasterXML/jackson) for JSON processing. It provides both synchronous and asynchronous APIs to interact with any ACP-compatible agent (e.g. [OpenCode](https://opencode.ai/), [Claude Code](https://www.npmjs.com/package/@agentclientprotocol/claude-agent-acp), [Pi](https://github.com/svkozak/pi-acp), [Gemini](https://geminicli.com/)) using stdio.

The project implements the [ACP Schema Specification v1](https://agentclientprotocol.com/specification). The JSON schema definition is bundled at `schema/src/main/resources/schema/acp/v1/schema.json` and Java records are generated from it using `JSonSchemaGenerator` (a custom code generator included in the `schema` module). See [CONTRIBUTING.md](CONTRIBUTING.md) for details on regenerating schema classes.

## Project structure

| Module     | Artifact ID      | Description                                                                                                      |
|------------|------------------|------------------------------------------------------------------------------------------------------------------|
| `schema`   | `acp-schema`     | ACP JSON Schema (`v1`), generated Java records/enums, and `JSonSchemaGenerator` code generator                   |
| `registry` | `acp-registry`   | Agent registry: discovery, installation (binary/npx/uvx) and resolution of ACP agents                           |
| `core`     | `acp-core`       | ACP client library: fluent builder, session workflow, notification router, stdio transport                       |
| `client`   | `acp-client`     | Aesh CLI (`AcpCommand`), skills, and sandbox. Depends on `core` and `registry`. Built as Quarkus uber-jar        |

## Prerequisites

- [JDK 21+](https://openjdk.org/)
- [Apache Maven 3.9+](https://maven.apache.org/)
- Any ACP-compatible agent (see [Agents and providers](#agents-and-providers) for the list of some agents and how to install them)
- (Optional) [JBang](https://www.jbang.dev/) for running the CLI via catalog

## Build

Compile the project and build the uber-jar:
```shell
mvn clean install
```

## Core library

The `core` module (`acp-core`) provides a fluent Java API to build ACP clients, configure notification handling, and run the full session lifecycle.

### Creating a client

Use `AcpClient.sync()` or `AcpClient.async()` to create a builder. The builder supports fluent configuration of timeouts, typed notification handlers, and permission handling.

```java
var transport = new StdioAcpClientTransport(agentParams);

try (AcpSyncClient client = AcpClient.sync(transport)
        .withRequestTimeout(Duration.ofSeconds(30))
        .withPromptRequestTimeout(Duration.ZERO)
        .withNotifications(n -> n
            .onAgentMessage(chunk -> System.out.print(extractText(chunk.content())))
            .onAgentThought(chunk -> logger.debug("[Thought] " + extractText(chunk.content())))
            .onToolCall(tc -> logger.info("[ToolCall] " + tc.title() + " - " + tc.status()))
            .onToolCallUpdate(tcu -> logger.info("[ToolUpdate] " + tcu.title() + " - " + tcu.status()))
            .onPlan(plan -> plan.entries().forEach(e -> logger.info("  - " + e.content())))
            .onUsage(usage -> logger.info("[Usage] used=" + usage.used() + " cost=" + usage.cost())))
        .withPermission(request -> handlePermission(request))
        .build()) {
    // client is connected and ready
}
```

#### Builder options

| Method | Description | Default |
|--------|-------------|---------|
| `withRequestTimeout(Duration)` | Timeout for JSON-RPC requests (initialize, session, config) | 30 seconds |
| `withPromptRequestTimeout(Duration)` | Timeout for prompt requests; `Duration.ZERO` means no timeout | `Duration.ZERO` |
| `withNotifications(Consumer<NotificationRouter>)` | Typed notification handlers (see below) | none |
| `onSessionUpdate(Consumer<SessionNotification>)` | Raw consumer for all session notifications | none |
| `withPermission(Function<RequestPermissionRequest, RequestPermissionResponse>)` | Handler for agent permission requests | auto-accept |

### Notification handling

Session updates streamed during prompt processing are dispatched to typed handlers registered via `withNotifications()`. Each handler receives a strongly-typed object:

```java
.withNotifications(n -> n
    .onAgentMessage(chunk -> { /* ContentChunk */ })
    .onAgentThought(chunk -> { /* ContentChunk */ })
    .onUserMessage(chunk -> { /* ContentChunk */ })
    .onToolCall(tc -> { /* ToolCall: title, kind, status */ })
    .onToolCallUpdate(tcu -> { /* ToolCallUpdate: title, status */ })
    .onPlan(plan -> { /* Plan: entries with content and status */ })
    .onAvailableCommands(cmds -> { /* AvailableCommandsUpdate */ })
    .onCurrentMode(mode -> { /* CurrentModeUpdate: currentModeId */ })
    .onConfigOption(config -> { /* ConfigOptionUpdate: configOptions */ })
    .onSessionInfo(info -> { /* SessionInfoUpdate */ })
    .onUsage(usage -> { /* UsageUpdate: used, size, cost */ }))
```

For advanced use cases requiring access to the raw notification (e.g. cross-cutting concerns between update types), use `onSessionUpdate()` instead of or in addition to typed handlers. When both are registered, typed handlers fire first, then the raw consumer fires for every notification.

### Session workflow

The `AcpSessionWorkflow` provides a fluent API for the common session lifecycle: **initialize** the agent, **create a session**, optionally **set the model** and **skill**, then **send a prompt**. The session is automatically closed when the workflow completes.

```java
AcpSessionResult result = client.workflow()
        .initialize()
        .newSession("/path/to/workspace")
        .model("claude-opus-4-6")
        .skill("/path/to/skill")
        .prompt("Refactor the service layer")
        .execute();
```

The workflow returns an `AcpSessionResult` containing all intermediate responses:

```java
// Agent metadata from the initialization handshake
Implementation agentInfo = result.agentInfo();
logger.info("Agent: " + agentInfo.name() + " v" + agentInfo.version());

// Session ID
String sessionId = result.sessionId();

// Effective config options (from model override or session defaults)
List<SessionConfigOption> options = result.configOptions();

// Prompt completion
StopReason stopReason = result.stopReason();
logger.info("Done! Stop reason: " + stopReason);

// Access full response objects for deeper inspection
InitializeResponse init = result.initializeResponse();
NewSessionResponse session = result.newSessionResponse();
PromptResponse prompt = result.promptResponse();
```

#### Workflow steps

| Method | Required | Description |
|--------|----------|-------------|
| `initialize()` | yes | Performs the ACP handshake with the agent |
| `newSession(String cwd)` | yes | Creates a session with the given workspace directory |
| `additionalDirectories(List)` | no | Exposes additional directories to the agent |
| `model(String)` | no | Sets the model (e.g. `"claude-opus-4-6"`). Silently skipped if the agent doesn't support config options |
| `skill(String)` | no | Appends skill instructions to the prompt |
| `prompt(String)` | yes | Sets the prompt text to send |
| `execute()` | -- | Runs the workflow and returns `AcpSessionResult` |

#### Lifecycle callbacks

For real-time logging or progress feedback between workflow steps, register callbacks:

```java
AcpSessionResult result = client.workflow()
        .initialize()
        .onInitialized(init -> {
            logger.info("Connected to: " + init.agentInfo().name());
            logger.info("Protocol version: " + init.protocolVersion());
        })
        .newSession(cwd)
        .onSessionCreated(session -> {
            logger.info("Session: " + session.sessionId());
        })
        .model("claude-opus-4-6")
        .skill("/path/to/skill")
        .prompt("Say hello")
        .beforePrompt(() -> System.out.println("Waiting for response..."))
        .execute();
```

| Callback | When it fires |
|----------|---------------|
| `onInitialized(Consumer<InitializeResponse>)` | After the handshake completes |
| `onSessionCreated(Consumer<NewSessionResponse>)` | After the session is created |
| `beforePrompt(Runnable)` | Right before the prompt is sent |

### Using the raw client API

For advanced scenarios (multiple prompts per session, cancellation, custom initialization), use the lower-level `AcpSyncClient` methods directly:

```java
try (AcpSyncClient client = AcpClient.sync(transport)
        .withNotifications(n -> n.onAgentMessage(chunk -> System.out.print(text)))
        .build()) {

    var init = client.initialize();
    var session = client.newSession(new NewSessionRequest("/workspace", List.of()));

    // Send multiple prompts in the same session
    var response1 = client.prompt(new PromptRequest(
            List.of(new TextContent("Create a REST endpoint")), session.sessionId()));
    var response2 = client.prompt(new PromptRequest(
            List.of(new TextContent("Now add tests for it")), session.sessionId()));

    client.closeSession(new CloseSessionRequest(session.sessionId()));
}
```

### Async client

For non-blocking composition using `CompletableFuture`:

```java
AcpAsyncClient client = AcpClient.async(transport)
        .withNotifications(n -> n.onToolCall(tc -> logger.info(tc.title())))
        .build();

client.connect()
    .thenCompose(v -> client.initialize())
    .thenCompose(init -> client.newSession(new NewSessionRequest("/workspace", List.of())))
    .thenCompose(session -> client.prompt(
            new PromptRequest(List.of(new TextContent("Hello")), session.sessionId())))
    .thenAccept(response -> logger.info("Done: " + response.stopReason()))
    .join();

client.closeGracefully().join();
```

## ACP CLI

The `client` module provides a CLI tool (`acp`) built on the core library. It wraps the fluent API into a single command that connects to any ACP-compatible agent, runs a prompt, and streams the output to the console.

### Running with `java -jar`

Export the current version from the project's clone:
```shell
export VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
```
Then execute one of the following commands:
```shell
# Default prompt: "Say Hello" with OpenCode agent
java -jar client/target/acp-java-client-${VERSION}-runner.jar

# Custom prompt
java -jar client/target/acp-java-client-${VERSION}-runner.jar --prompt "What is 6+6?"

# With a specific agent, provider, and model
java -jar client/target/acp-java-client-${VERSION}-runner.jar \
  --agent claude-acp \
  --provider vertex-ai \
  --model claude-opus-4-6 \
  --prompt "Say hello"
```

### Running with JBang

A [JBang catalog](https://www.jbang.dev/documentation/guide/latest/alias_catalogs.html) is provided at the project root. After building:

```shell
# Run from the project root using the local catalog and uber jar generated under client/target/
jbang acp --prompt "What is 6+6?"
```

To install the tool for use outside this project, use the Maven GAV with a released version:
```shell
jbang app install --name acp io.smallrye.ai:acp-java-client:0.1.0:runner

cd /java/project/to/code/using/ai
acp --prompt "Say hello"
```
The command supports autocompletion:
```shell
source <(acp generate-completion)
```

### Running with Quarkus dev mode

```shell
mvn quarkus:dev -pl client -Dquarkus.args="--prompt 'Say Hello'"
```

### Example output

```shell
11:11:57,492 INFO  [StdioAcpClientTransport] ACP agent starting
11:11:57,522 INFO  [StdioAcpClientTransport] ACP agent started
11:11:58,435 INFO  [AcpCommand] Connected to the ACP agent: OpenCode - v1.15.4
11:11:58,613 INFO  [AcpCommand] Session created: ses_1b631ae8bffegMSoAYKMCI6cUc
11:11:58,619 INFO  [AcpCommand] [Commands] Available:
11:11:58,622 INFO  [AcpCommand] Model: opencode/big-pickle
11:11:58,623 INFO  [AcpCommand] Sending prompt: Say Hello
Here is the AI response:
Hello
11:12:00,661 INFO  [AcpCommand] [Usage] used=8081 size=200000 cost={amount=0, currency=USD}
11:12:00,665 INFO  [AcpCommand] Done! Stop reason: END_TURN
11:12:00,676 INFO  [StdioAcpClientTransport] ACP agent process stopped (exit code 143)
```

### CLI options

Man pages for all commands and subcommands are available in the [docs/](docs/) directory.

Precedence: **CLI argument > environment variable > default value**.

| Option                      | Env Variable                  | Description                                                                                                                                                            | Default                      |
|-----------------------------|-------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------|
| `-a`, `--agent`             | `ACP_AGENT`                   | ACP compatible agent id (see registry list)                                                                                                                            | `opencode`                   |
| `-p`, `--prompt`            | `ACP_PROMPT`                  | The prompt text to send to the agent                                                                                                                                   | `Say Hello`                  |
| `--provider`                | `ACP_PROVIDER`                | Provider: `zen`, `vertex-ai`                                                                                                                                           | `zen`                        |
| `-m`, `--model`             | `ACP_MODEL`                   | The model to use, e.g. `claude-opus-4-6` (resolved per agent/provider)                                                                                                 |                              |
| `--agent-binary`            | `ACP_AGENT_BINARY`            | Override agent binary path (for custom agents)                                                                                                                         |                              |
| `--agent-args`              | `ACP_AGENT_ARGS`              | Override agent arguments (for custom agents)                                                                                                                           |                              |
| `--request-timeout`         | `ACP_REQUEST_TIMEOUT`         | Timeout in seconds for steps: initialize, create session, etc.                                                                                                         | `30`                         |
| `--prompt-request-timeout`  | `ACP_PROMPT_REQUEST_TIMEOUT`  | Timeout in seconds for prompt requests; 0 means no timeout                                                                                                             | `0`                          |
| `--permission-mode`         | `ACP_PERMISSION_MODE`         | How to respond to agent permission requests (see below)                                                                                                                | `allow_always`               |
| `-s`, `--skill-path`        | `SKILL_PATH`                  | Path or URL to a skills folder appended to the prompt                                                                                                                  |                              |
| `-b`, `--backup`            | `ACP_BACKUP`                  | Backup workspace to `target/workdirs` before running: `yes`, `no`. Only applies to Maven/Gradle projects. When enabled, the session CWD is set to the backup directory | `yes`                        |
| `--backup-project-name`     | `ACP_BACKUP_PROJECT_NAME`     | Name of the project used in the backup directory: `target/workdirs/<name>_<timestamp>`                                                                                 | `.` (current directory name) |
| `--wks`, `--workspace-path` | `WORKSPACE_PATH`              | Absolute path to the project/workspace directory used as CWD for the session                                                                                           | current directory            |
| `-l`, `--log-level`         | `ACP_LOG_LEVEL`               | Log level: `INFO`, `DEBUG`, `TRACE`, `WARNING`, `SEVERE`                                                                                                               | `INFO`                       |
| `-h`, `--help`              |                               | Show help message and exit                                                                                                                                             |                              |

The `--agent` option resolves the binary and arguments automatically from a built-in registry. For custom or unsupported agents, use `--agent-binary` and `--agent-args` instead.

When using `--agent opencode` with `--provider vertex-ai`, simple model names are resolved automatically:
`--model claude-opus-4-6` becomes `google-vertex-anthropic/claude-opus-4-6@default`.

### CLI examples

For more detailed command examples per agent and provider, see [COMMANDS_EXAMPLE.md](COMMANDS_EXAMPLE.md).

```shell
# OpenCode with Zen (default agent + provider)
acp --prompt "Say Hello"

# Claude Code with Vertex AI
acp --agent claude-acp --provider vertex-ai --model claude-opus-4-6 \
  --prompt "Say Hello"

# Using environment variables
export ACP_AGENT=claude-acp
export ACP_PROVIDER=vertex-ai
export ACP_MODEL=claude-opus-4-6
acp --prompt "Execute the java-project-discovery skill."

# Gemini CLI
acp --agent gemini --prompt "Say Hello"

# Custom agent binary
acp --agent-binary my-agent --agent-args "serve" --prompt "Say Hello"

# With a skill path
acp --agent claude-acp --skill-path /path/to/skills --prompt "Follow the skill instructions"

# With a skill URL (cloned automatically)
acp --agent claude-acp --skill-path https://github.com/org/skills-repo --prompt "Follow the skill"
```

## Agents and providers

### ACP agents

The following ACP-compatible agents can be used with this client. Install the agent you need and pass its binary and args via the `--agent-binary` and `--agent-args` CLI options.

| Agent       | Binary (`--agent-binary`) | Args (`--agent-args`) | Installation                                                                                                                       |
|-------------|---------------------------|-----------------------|------------------------------------------------------------------------------------------------------------------------------------|
| OpenCode    | `opencode`                | `acp`                 | See [OpenCode ACP docs](https://opencode.ai/docs/acp/)                                                                            |
| Claude Code | `claude-agent-acp`        |                       | `npm install -g @agentclientprotocol/claude-agent-acp` ([docs](https://www.npmjs.com/package/@agentclientprotocol/claude-agent-acp)) |
| Pi          | `pi-acp`                  |                       | `npm install -g pi-acp` ([docs](https://github.com/svkozak/pi-acp))                                                               |
| Gemini CLI  | `gemini`                  | `--acp`               | `npm install -g @google/gemini-cli` ([docs](https://geminicli.com/docs/cli/acp-mode/))                                             |

### Providers

Each agent can be configured with a model provider. The `--provider` option controls which environment variables are validated before connecting. The client will exit with an error if any required variable is missing.

| Agent       | Provider (`--provider`) | Environment variables                                                                         | Documentation                                                                         |
|-------------|-------------|-----------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------|
| OpenCode    | `zen` (default) | none                                                                                          | [OpenCode Zen](https://opencode.ai/docs/zen/)                                        |
| OpenCode    | `vertex-ai` | `GOOGLE_APPLICATION_CREDENTIALS`, `VERTEX_LOCATION`, `GOOGLE_CLOUD_PROJECT`                   | [Google Vertex AI](https://opencode.ai/docs/providers/#google-vertex-ai)              |
| Claude Code | `vertex-ai` | `ANTHROPIC_VERTEX_PROJECT_ID`, `ANTHROPIC_MODEL`, `CLAUDE_CODE_USE_VERTEX`, `CLOUD_ML_REGION` | [Anthropic Vertex AI](https://docs.anthropic.com/en/docs/build-with-claude/vertex-ai) |
| Pi          | `vertex-ai` | `GOOGLE_APPLICATION_CREDENTIALS`, `GOOGLE_CLOUD_PROJECT`, `CLOUD_ML_REGION`                   | [pi-vertex-claude](https://github.com/isaacraja/pi-vertex-claude)                    |
| Gemini CLI  | `vertex-ai` | `GOOGLE_CLOUD_PROJECT`, `GOOGLE_CLOUD_LOCATION`                                               | [Gemini CLI ACP mode](https://geminicli.com/docs/cli/acp-mode/)                      |

## Permissions

When an agent needs to perform a sensitive operation (e.g. writing a file, running a command), it sends a `session/request_permission` request. The client responds automatically based on the `--permission-mode` value:

| Mode           | Behavior                                            |
|----------------|-----------------------------------------------------|
| `allow_always` | Accept and remember the choice (default)            |
| `allow_once`   | Accept only this time                               |
| `reject_once`  | Reject only this time                               |
| `reject_always`| Reject and remember the choice                      |

Example:
```shell
java -jar client/target/acp-java-client-${VERSION}-runner.jar \
  --permission-mode allow_once \
  --prompt "Create a Java HelloWorld class"
```

## Workspace path and backup

### Workspace path

The `--workspace-path` option sets the project directory used as CWD for the agent session. If not specified, it defaults to the directory where the command is executed.

```shell
# Run the agent against a different project directory
acp --agent claude-acp --workspace-path /path/to/my-project --prompt "Say hello"

# Using an environment variable
export WORKSPACE_PATH=/path/to/my-project
acp --agent claude-acp --prompt "Say hello"
```

### Workspace backup

When running against a Maven or Gradle project, the client automatically backs up the workspace before starting the agent session. This creates a timestamped copy of your source files under `target/workdirs/`, allowing you to restore the original state if the agent makes undesired changes. When backup is enabled and succeeds, the session CWD is automatically set to the backup directory so the agent works on the copy.

- **Enabled by default** (`--backup yes`)
- **Only applies** to directories containing `pom.xml`, `build.gradle`, or `build.gradle.kts`
- **Backup path**: `target/workdirs/<workspace-name>_<yyyyMMdd-HHmmss>`
- **CWD moves to backup**: when backup succeeds, the agent session CWD points to the backup directory
- **`--backup-project-name`** defaults to `.`, which resolves to the current directory name. Override it when running the same command against multiple projects in a shared workspace
- **Excludes** build output and metadata directories: `target/`, `build/`, `.git/`, `.gradle/`, `.idea/`, `node_modules/`
- **Skipped silently** for non-Maven/Gradle workspaces regardless of the flag value

```shell
# Backup is enabled by default -- uses current directory name
# CWD is set to the backup directory
acp --agent claude-acp --prompt "Refactor the service layer"
# -> CWD: target/workdirs/my-project_20260526-143022/

# Specify a backup project name (useful when running against multiple projects)
acp --agent claude-acp --backup-project-name my-service --prompt "Migrate to Jakarta"
# -> CWD: target/workdirs/my-service_20260526-143022/

# Combine workspace-path with backup
acp --agent claude-acp --workspace-path /path/to/my-project --prompt "Refactor"
# -> CWD: /path/to/my-project/target/workdirs/my-project_20260526-143022/

# Disable backup -- CWD stays as workspace-path or current directory
acp --agent claude-acp --backup no --prompt "Refactor the service layer"
```

## Logging

The project uses Quarkus logging (backed by [JBoss Log Manager](https://github.com/jboss-logging/jboss-logmanager)). By default, only `INFO`-level messages are shown (connection status, prompt lifecycle). Session update details (thoughts, tool calls, plans, commands, usage) and protocol internals are logged at `DEBUG` or `TRACE` level.

Log levels are configured in `client/src/main/resources/application.properties`. You can also override them on the command line:

```shell
# Enable debug logging
java -Dquarkus.log.category.\"io.smallrye\".level=DEBUG \
  -jar client/target/acp-java-client-${VERSION}-runner.jar \
  --prompt "Say Hello"

# Enable trace logging (raw JSON-RPC messages)
java -Dquarkus.log.category.\"io.smallrye\".level=TRACE \
  -jar client/target/acp-java-client-${VERSION}-runner.jar \
  --prompt "Say Hello"
```

### Log levels

| Level | What you see |
|-------|-------------|
| `INFO` (default) | Connected to agent, sending prompt, stop reason |
| `DEBUG` | + agent thoughts, tool calls, plans, commands, mode changes, usage, capabilities, session ID |
| `TRACE` | + raw JSON-RPC messages sent/received by the transport |
