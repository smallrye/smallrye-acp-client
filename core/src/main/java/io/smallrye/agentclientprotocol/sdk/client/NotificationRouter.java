package io.smallrye.agentclientprotocol.sdk.client;

import java.util.function.Consumer;

import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.*;

/**
 * Fluent sub-builder that routes {@link SessionNotification} updates to per-type consumers.
 *
 * <p>
 * Used via {@link AcpClient.AbstractBuilder#withNotifications(Consumer)}:
 *
 * <pre>{@code
 * AcpClient.sync(transport)
 *         .withNotifications(n -> n
 *                 .onAgentMessage(chunk -> System.out.print(text))
 *                 .onToolCall(tc -> logger.info(tc.title()))
 *                 .onUsage(usage -> logger.info(usage.used())))
 *         .build()
 * }</pre>
 *
 * <p>
 * Dispatches based on the {@code sessionUpdate} discriminator in the notification metadata.
 * Unhandled update types are silently ignored.
 */
public class NotificationRouter implements Consumer<SessionNotification> {

    private Consumer<ContentChunk> onAgentMessage;
    private Consumer<ContentChunk> onAgentThought;
    private Consumer<ContentChunk> onUserMessage;
    private Consumer<ToolCall> onToolCall;
    private Consumer<ToolCallUpdate> onToolCallUpdate;
    private Consumer<Plan> onPlan;
    private Consumer<AvailableCommandsUpdate> onAvailableCommands;
    private Consumer<CurrentModeUpdate> onCurrentMode;
    private Consumer<ConfigOptionUpdate> onConfigOption;
    private Consumer<SessionInfoUpdate> onSessionInfo;
    private Consumer<UsageUpdate> onUsage;

    NotificationRouter() {
    }

    /**
     * Registers a handler for agent message content chunks.
     */
    public NotificationRouter onAgentMessage(Consumer<ContentChunk> handler) {
        this.onAgentMessage = handler;
        return this;
    }

    /**
     * Registers a handler for agent thought content chunks.
     */
    public NotificationRouter onAgentThought(Consumer<ContentChunk> handler) {
        this.onAgentThought = handler;
        return this;
    }

    /**
     * Registers a handler for user message content chunks.
     */
    public NotificationRouter onUserMessage(Consumer<ContentChunk> handler) {
        this.onUserMessage = handler;
        return this;
    }

    /**
     * Registers a handler for tool call notifications.
     */
    public NotificationRouter onToolCall(Consumer<ToolCall> handler) {
        this.onToolCall = handler;
        return this;
    }

    /**
     * Registers a handler for tool call progress updates.
     */
    public NotificationRouter onToolCallUpdate(Consumer<ToolCallUpdate> handler) {
        this.onToolCallUpdate = handler;
        return this;
    }

    /**
     * Registers a handler for plan notifications.
     */
    public NotificationRouter onPlan(Consumer<Plan> handler) {
        this.onPlan = handler;
        return this;
    }

    /**
     * Registers a handler for available commands updates.
     */
    public NotificationRouter onAvailableCommands(Consumer<AvailableCommandsUpdate> handler) {
        this.onAvailableCommands = handler;
        return this;
    }

    /**
     * Registers a handler for current mode updates.
     */
    public NotificationRouter onCurrentMode(Consumer<CurrentModeUpdate> handler) {
        this.onCurrentMode = handler;
        return this;
    }

    /**
     * Registers a handler for config option updates.
     */
    public NotificationRouter onConfigOption(Consumer<ConfigOptionUpdate> handler) {
        this.onConfigOption = handler;
        return this;
    }

    /**
     * Registers a handler for session info updates.
     */
    public NotificationRouter onSessionInfo(Consumer<SessionInfoUpdate> handler) {
        this.onSessionInfo = handler;
        return this;
    }

    /**
     * Registers a handler for usage updates (token counts, cost).
     */
    public NotificationRouter onUsage(Consumer<UsageUpdate> handler) {
        this.onUsage = handler;
        return this;
    }

    @Override
    public void accept(SessionNotification notification) {
        String updateType = notification.meta() != null
                ? (String) notification.meta().get("sessionUpdate")
                : null;
        Object update = notification.update();

        if (update == null) {
            return;
        }

        if (update instanceof ContentChunk chunk) {
            if (updateType != null) {
                switch (updateType) {
                    case "agent_message_chunk" -> {
                        if (onAgentMessage != null)
                            onAgentMessage.accept(chunk);
                    }
                    case "agent_thought_chunk" -> {
                        if (onAgentThought != null)
                            onAgentThought.accept(chunk);
                    }
                    case "user_message_chunk" -> {
                        if (onUserMessage != null)
                            onUserMessage.accept(chunk);
                    }
                    default -> {
                    }
                }
            }
        } else if (update instanceof ToolCall tc && onToolCall != null) {
            onToolCall.accept(tc);
        } else if (update instanceof ToolCallUpdate tcu && onToolCallUpdate != null) {
            onToolCallUpdate.accept(tcu);
        } else if (update instanceof Plan p && onPlan != null) {
            onPlan.accept(p);
        } else if (update instanceof AvailableCommandsUpdate cmds && onAvailableCommands != null) {
            onAvailableCommands.accept(cmds);
        } else if (update instanceof CurrentModeUpdate mode && onCurrentMode != null) {
            onCurrentMode.accept(mode);
        } else if (update instanceof ConfigOptionUpdate config && onConfigOption != null) {
            onConfigOption.accept(config);
        } else if (update instanceof SessionInfoUpdate info && onSessionInfo != null) {
            onSessionInfo.accept(info);
        } else if (update instanceof UsageUpdate usage && onUsage != null) {
            onUsage.accept(usage);
        }
    }

    boolean hasAnyHandler() {
        return onAgentMessage != null || onAgentThought != null || onUserMessage != null
                || onToolCall != null || onToolCallUpdate != null || onPlan != null
                || onAvailableCommands != null || onCurrentMode != null || onConfigOption != null
                || onSessionInfo != null || onUsage != null;
    }
}
