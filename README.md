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
| `client`   | `acp-client`     | Aesh CLI (`AcpCommands`): `run`, `reg`, `model` subcommands. Depends on `core` and `registry`. Built as Quarkus uber-jar |

## Prerequisites

- [JDK 21+](https://openjdk.org/)
- [Apache Maven 3.9+](https://maven.apache.org/)
- Any ACP-compatible agent (see [Agents and providers](#agents-and-providers) for the list of some agents and how to install them)
- (Optional) [JBang](https://www.jbang.dev/) for running the CLI via catalog

## Core library

The `core` module (`acp-core`) provides a fluent Java API to build ACP clients, configure notification handling, and run the full session lifecycle.

### Creating a client

Use `AcpClient.sync()` or `AcpClient.async()` to create a builder. The builder supports fluent configuration of timeouts, typed notification handlers, and permission handling.

```java
 var agentParams = AgentParameters.builder("opencode")
              .arg("acp")
              .addEnvVar("OPENCODE_MODEL", "anthropic/claude-sonnet")
              .build();

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
        .onPermissionRequest((request, selectedOptionId) ->
            logger.info("[Permission] " + request.toolCall().title() + " -> " + selectedOptionId))
        .withPermissionMode("allow_always")
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
| `onPermissionRequest(BiConsumer<RequestPermissionRequest, String>)` | Observer called when the agent requests permission. Receives the request and the selected option ID. Does not change how permissions are resolved | none |
| `withPermissionMode(String)` | Permission mode: `"allow_always"`, `"allow_once"`, `"reject_once"`, `"reject_always"` | `"allow_always"` |

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

The `AcpSessionWorkflow` provides a fluent API for the common session lifecycle: **initialize** the agent, **create a session**, optionally **set the model** and **skill**, then **send a prompt**. Initialization and session creation are handled automatically. The session is automatically closed when the workflow completes.

```java
AcpSessionResult result = client.workflow()
        .withWorkspace("/path/to/workspace")
        .mcpServer(new McpServerStdio(
                List.of("--stdio"), "/path/to/mcp-server", List.of(), "filesystem"))
        .model("claude-opus-4-6")
        .skill("/path/to/skill")
        .prompt("Refactor the service layer")
        .run();
```

The `mcpServer()` call is optional. It accepts any of the transport types defined by the ACP schema:

| Record | Transport | Required fields |
|--------|-----------|-----------------|
| `McpServerStdio` | stdio | `name`, `command`, `args`, `env` |
| `McpServerHttp` | HTTP | `name`, `url`, `headers` |

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
| `withWorkspace(String cwd)` | no | Sets the workspace directory. Defaults to `System.getProperty("user.dir")` |
| `resumeSession(String sessionId)` | no | Resumes an existing session instead of creating a new one |
| `mcpServer(Object)` / `mcpServers(List)` | no | MCP servers the agent should connect to (`McpServerStdio` or `McpServerHttp`) |
| `model(String)` | no | Sets the model (e.g. `"claude-opus-4-6"`). Silently skipped if the agent doesn't support config options |
| `skill(String)` | no | Appends skill instructions to the prompt |
| `prompt(String)` | yes | Sets the prompt text to send |
| `run()` | -- | Runs the workflow and returns `AcpSessionResult` |

#### Lifecycle callbacks

For real-time logging or progress feedback between workflow steps, register callbacks:

```java
AcpSessionResult result = client.workflow()
        .withWorkspace(cwd)
        .onInitialized(init -> {
            logger.info("Connected to: " + init.agentInfo().name());
            logger.info("Protocol version: " + init.protocolVersion());
        })
        .onSessionCreated(session -> {
            logger.info("Session: " + session.sessionId());
        })
        .model("claude-opus-4-6")
        .skill("/path/to/skill")
        .prompt("Say hello")
        .beforePrompt(() -> System.out.println("Waiting for response..."))
        .run();
```

| Callback | When it fires |
|----------|---------------|
| `onInitialized(Consumer<InitializeResponse>)` | After the handshake completes |
| `onSessionCreated(Consumer<NewSessionResponse>)` | After a new session is created |
| `onSessionLoaded(Consumer<LoadSessionResponse>)` | After a resumed session is loaded |
| `beforePrompt(Runnable)` | Right before the prompt is sent |

#### Resuming a session

To resume an existing session, call `resumeSession(sessionId)`. The workflow will call `session/list` to verify the session exists, then `session/load` to restore it:

```java
AcpSessionResult result = client.workflow()
        .withWorkspace("/path/to/workspace")
        .resumeSession("session-id-to-resume")
        .onSessionLoaded(loaded -> logger.info("Session resumed"))
        .prompt("Continue where we left off")
        .run();

// Check if the session was resumed
if (result.isResumedSession()) {
    logger.info("Resumed session successfully");
}
```

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

### Raw JSON-RPC message listeners

For protocol-level debugging or building JSON output modes, attach raw message listeners to the transport before connecting. These fire with the serialized JSON string before parsing (inbound) or after serialization (outbound):

```java
var transport = new StdioAcpClientTransport(agentParams);
transport.setRawInboundListener(json -> System.out.println("IN:  " + json));
transport.setRawOutboundListener(json -> System.out.println("OUT: " + json));

try (AcpSyncClient client = AcpClient.sync(transport)
        .withNotifications(n -> { /* silent */ })
        .build()) {
    // all JSON-RPC messages are printed to stdout
}
```

## ACP CLI

The `client` module provides a Quarkus client tool (`acp`) built using the modules: `core` and `registry` and [Aesh](https://github.com/aeshell/aesh) to design the 
commands. See commands documentation [page](docs/acp.adoc) for more details.

### Build

Compile the project and build the uber-jar:
```shell
mvn clean install
```

### Running with Quarkus dev mode

```shell
mvn quarkus:dev -pl client -Dquarkus.args="run -p 'Say Hello'"
```

### Running with `java -jar`

Export the current version of the project:
```shell
export VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
```
Then execute one of the following commands:
```shell
# Custom prompt
java -jar client/target/acp-java-client-${VERSION}-runner.jar run -p "What is 6+6?"

# With a specific agent, provider, and model
java -jar client/target/acp-java-client-${VERSION}-runner.jar run \
  --agent claude-acp \
  --provider vertex-ai \
  --model claude-opus-4-6 \
  -p "Say hello"

# List models available for an installed agent
java -jar client/target/acp-java-client-${VERSION}-runner.jar model list -a opencode
Fetching ACP Agent models ...

List of LLM models available for the agent 'opencode':

  NAME                                                         VALUE TO BE USED
  ------------------------------------------------------------------------------------------
  Vertex/Claude Fable 5                                        google-vertex/claude-fable-5@default
  Vertex/Claude Fable 5.1                                      google-vertex/claude-fable-5-1@default
...  
```

### Running with JBang

- Option A: using local catalog
A [JBang catalog](https://www.jbang.dev/documentation/jbang/latest/alias_catalogs.html) file is provided at the project root: `jbang-catalog.json` 

```json
{
  "aliases": {
    "acp": {
      "script-ref": "client/target/acp-java-client-0.1.2-SNAPSHOT-runner.jar",
      "description": "ACP Java Client for any ACP-compatible agent"
    }
  }
}
```

and can be used to install the client using a project build locally.

```shell
jbang acp run -p "What is 6+6?"
```

- Option B: using released version

To install the client using a released version published on maven central, execute the following command:
```shell
jbang app install --name acp io.smallrye.ai:acp-java-client:0.1.1:runner

cd /java/project/to/code/using/ai
acp run -p "Say hello"
```

### Output modes

The CLI supports three output modes that control what is printed to the console. The mode is selected via the `-o` / `--output` and `-v` / `--verbose` flags.

| Mode | Flag | Agent messages | Notifications | Log output |
|------|------|----------------|---------------|------------|
| **default** | _(none)_ | streamed to stdout | silent (DEBUG level) | suppressed (WARNING) |
| **verbose** | `-v` | streamed to stdout | logged at INFO | INFO level |
| **json** | `-o json` | suppressed | suppressed | suppressed — raw JSON-RPC lines to stdout |

- **default** — clean human-friendly output. Only the agent's response text is printed. Notifications (tool calls, plans, usage, etc.) and lifecycle events are logged at DEBUG level, invisible unless you also pass `--log-level DEBUG`.

```shell
$ acp run -p "Say Hello"
Starting the AI conversation ...
Hello, world!
```

- **verbose** (`-v`) — everything from default, plus detailed INFO-level logging of all notifications: tool calls with rawInput/rawOutput, plans with priority, permissions, session info, usage, and thoughts.

```shell
$ acp run -v -p "Say Hello using user's machine language"
Starting the AI conversation ...
15:27:22,492 INFO  [RunCommand] [Usage] used=24378 size=200000 cost=null
15:27:22,496 INFO  [RunCommand] [Thought] The user wants me to
gr
15:27:22,918 INFO  [RunCommand] [Thought] eet them in
15:27:22,919 INFO  [RunCommand] [Thought]  their machine's language. Let
15:27:22,920 INFO  [RunCommand] [Thought]  me check
15:27:22,920 INFO  [RunCommand] [Thought]  their
15:27:22,920 INFO  [RunCommand] [Thought]  environment
15:27:22,921 INFO  [RunCommand] [Thought]  for
15:27:22,921 INFO  [RunCommand] [Thought]  locale
15:27:22,921 INFO  [RunCommand] [Thought] /
15:27:23,382 INFO  [RunCommand] [Thought] language settings.
15:27:23,401 INFO  [RunCommand] [ToolCall] id=toolu_vrtx_01CXUJqWYK52Xm3MbrWaxC1R title=Terminal kind=EXECUTE status=PENDING
15:27:23,401 INFO  [RunCommand] [ToolCall]   rawInput: {}
15:27:23,822 INFO  [RunCommand] [ToolUpdate] id=toolu_vrtx_01CXUJqWYK52Xm3MbrWaxC1R title=defaults read -g AppleLocale 2>/dev/null || echo "unknown" status=null
15:27:23,823 INFO  [RunCommand] [ToolUpdate]   rawInput: {command=defaults read -g AppleLocale 2>/dev/null || echo "unknown"}
15:27:23,866 INFO  [RunCommand] [ToolUpdate] id=toolu_vrtx_01CXUJqWYK52Xm3MbrWaxC1R title=defaults read -g AppleLocale 2>/dev/null || echo "unknown" status=null
15:27:23,866 INFO  [RunCommand] [ToolUpdate]   rawInput: {command=defaults read -g AppleLocale 2>/dev/null || echo "unknown", description=Check macOS locale setting}
15:27:23,866 INFO  [RunCommand] [ToolUpdate]   content: [{type=content, content={type=text, text=Check macOS locale setting}}]
15:27:23,873 INFO  [RunCommand] [Usage] used=24496 size=200000 cost=null
15:27:23,922 INFO  [RunCommand] [Permission] id=toolu_vrtx_01CXUJqWYK52Xm3MbrWaxC1R title=defaults read -g AppleLocale 2>/dev/null || echo "unknown" kind=EXECUTE
15:27:23,923 INFO  [RunCommand] [Permission]   rawInput: {command=defaults read -g AppleLocale 2>/dev/null || echo "unknown", description=Check macOS locale setting}
15:27:23,923 INFO  [RunCommand] [Permission]   option: Deny (reject_once) id=reject
15:27:23,923 INFO  [RunCommand] [Permission]   option: Allow Once (allow_once) id=allow
15:27:23,923 INFO  [RunCommand] [Permission]   option: Always Allow (allow_always) id=allow_always
15:27:23,923 INFO  [RunCommand] [Permission]   selected: allow_always
15:27:26,806 INFO  [RunCommand] [ToolUpdate] id=toolu_vrtx_01CXUJqWYK52Xm3MbrWaxC1R title=null status=null
15:27:26,823 INFO  [RunCommand] [ToolUpdate] id=toolu_vrtx_01CXUJqWYK52Xm3MbrWaxC1R title=null status=COMPLETED
15:27:26,823 INFO  [RunCommand] [ToolUpdate]   rawOutput: en_BE
15:27:26,823 INFO  [RunCommand] [ToolUpdate]   content: [{type=content, content={type=text, text=```console
en_BE
```}}]
15:27:28,242 INFO  [RunCommand] [Usage] used=24511 size=200000
cost=null
Your locale is `en_BE` (English - Belgium).

Hello! 👋 How can I help you today?

15:27:28,828 INFO  [RunCommand] [Usage] used=24540 size=200000 cost=null
15:27:28,875 INFO  [RunCommand] [Usage] used=24540 size=200000 cost={amount=0.087753,
currency=USD}
15:27:28,879 INFO  [RunCommand] [SessionInfo] title=Say Hello using user's machine language updatedAt=2026-09-17T13:27:26.924Z
15:27:28,892 INFO  [quarkus] acp-java-client stopped in 0.004s 
```

- **json** (`-o json`) — raw JSON-RPC protocol lines (both inbound and outbound) are printed to stdout, one per line. All log output is suppressed. Designed for machine consumption and debugging.

```shell
$ acp run -o json -p "Say Hello"
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}
{"jsonrpc":"2.0","id":1,"result":{...}}
...
```

### CLI examples

For more detailed command examples per agent and provider, see [COMMANDS_EXAMPLE.md](COMMANDS_EXAMPLE.md).

```shell
# Claude agent with default model
acp run -p "Say Hello"

# Claude agent with Vertex AI provider
acp run -a claude-acp --provider vertex-ai --model claude-opus-4-6 \
  -p "Say Hello"

# Using environment variables
export ACP_AGENT=claude-acp
export ACP_PROVIDER=vertex-ai
export ACP_MODEL=claude-opus-4-6
acp run -p "Execute the java-project-discovery skill."

# Gemini CLI
acp run -a gemini -p "Say Hello"

# Custom agent binary
acp run --agent-binary my-agent --agent-args "serve" -p "Say Hello"

# With a skill path
acp run -a claude-acp --skill-path /path/to/skills -p "Follow the skill instructions"

# With a skill URL (cloned automatically)
acp run -a claude-acp --skill-path https://github.com/org/skills-repo -p "Follow the skill"
```


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
java -jar client/target/acp-java-client-${VERSION}-runner.jar run \
  --permission-mode allow_once \
  -p "Create a Java HelloWorld class"
```

## Workspace path and backup

### Workspace path

The `--workspace-path` option sets the project directory used as CWD for the agent session. If not specified, it defaults to the directory where the command is executed.

```shell
# Run the agent against a different project directory
acp run -a claude-acp --workspace-path /path/to/my-project -p "Say hello"

# Using an environment variable
export WORKSPACE_PATH=/path/to/my-project
acp run -a claude-acp -p "Say hello"
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
acp run -a claude-acp -p "Refactor the service layer"
# -> CWD: target/workdirs/my-project_20260526-143022/

# Specify a backup project name (useful when running against multiple projects)
acp run -a claude-acp --backup-project-name my-service -p "Migrate to Jakarta"
# -> CWD: target/workdirs/my-service_20260526-143022/

# Combine workspace-path with backup
acp run -a claude-acp --workspace-path /path/to/my-project -p "Refactor"
# -> CWD: /path/to/my-project/target/workdirs/my-project_20260526-143022/

# Disable backup -- CWD stays as workspace-path or current directory
acp run -a claude-acp --backup no -p "Refactor the service layer"
```

## Logging

The project uses Quarkus logging (backed by [JBoss Log Manager](https://github.com/jboss-logging/jboss-logmanager)). By default, log output is suppressed (`WARNING` level) so the CLI stays clean. Use `-v` (verbose) to enable INFO-level notification logging, or `-l` / `--log-level` to set an explicit level.

```shell
# Verbose mode — notifications at INFO level
acp run -v -p "Say Hello"

# Explicit debug level — notifications + lifecycle details
acp run --log-level DEBUG -p "Say Hello"

# Trace level — raw JSON-RPC messages sent/received by the transport
acp run --log-level TRACE -p "Say Hello"

# JSON output — raw protocol lines, no logs
acp run -o json -p "Say Hello"
```

Precedence: `-o json` wins (all logging suppressed), then `--log-level` (explicit level), then `-v` (INFO).

### Log levels

| Level | What you see |
|-------|-------------|
| `WARNING` (default) | No log output — only agent messages appear |
| `INFO` (`-v`) | + tool calls, plans, commands, mode changes, usage, permissions, session info |
| `DEBUG` | + agent thoughts, capabilities, session ID, workspace paths, lifecycle events |
| `TRACE` | + raw JSON-RPC messages sent/received by the transport |


## Agents and providers

### ACP agents

The list of the ACP-compatible agents is published part the [ACP registry](https://agentclientprotocol.com/get-started/registry).

### Providers

Each agent can be configured with a LLM provider. The `--provider` option controls which environment variables are validated before connecting. The client will exit with an error if any required variable is missing.

| Agent       | Provider (`--provider`) | Environment variables                                                                         | Documentation                                                                         |
|-------------|-------------|-----------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------|
| OpenCode    | `zen` (default) | none                                                                                          | [OpenCode Zen](https://opencode.ai/docs/zen/)                                        |
| OpenCode    | `vertex-ai` | `GOOGLE_APPLICATION_CREDENTIALS`, `VERTEX_LOCATION`, `GOOGLE_CLOUD_PROJECT`                   | [Google Vertex AI](https://opencode.ai/docs/providers/#google-vertex-ai)              |
| Claude Code | `vertex-ai` | `ANTHROPIC_VERTEX_PROJECT_ID`, `ANTHROPIC_MODEL`, `CLAUDE_CODE_USE_VERTEX`, `CLOUD_ML_REGION` | [Anthropic Vertex AI](https://docs.anthropic.com/en/docs/build-with-claude/vertex-ai) |
| Pi          | `vertex-ai` | `GOOGLE_APPLICATION_CREDENTIALS`, `GOOGLE_CLOUD_PROJECT`, `CLOUD_ML_REGION`                   | [pi-vertex-claude](https://github.com/isaacraja/pi-vertex-claude)                    |
| Gemini CLI  | `vertex-ai` | `GOOGLE_CLOUD_PROJECT`, `GOOGLE_CLOUD_LOCATION`                                               | [Gemini CLI ACP mode](https://geminicli.com/docs/cli/acp-mode/)                      |