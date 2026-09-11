package io.smallrye.agentclientprotocol.sdk.client;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;

import io.smallrye.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.*;

/**
 * Factory for creating ACP clients with a fluent builder API.
 *
 * <p>
 * Use {@link #sync(StdioAcpClientTransport)} for blocking operations
 * or {@link #async(StdioAcpClientTransport)} for non-blocking operations.
 *
 * <p>
 * Example:
 *
 * <pre>{@code
 * try (AcpSyncClient client = AcpClient.sync(transport)
 *         .withRequestTimeout(Duration.ofSeconds(30))
 *         .withNotifications(n -> n
 *                 .onAgentMessage(chunk -> System.out.print(extractText(chunk.content())))
 *                 .onToolCall(tc -> logger.info("[ToolCall] " + tc.title()))
 *                 .onUsage(usage -> logger.info("[Usage] " + usage.used())))
 *         .withPermission(request -> handlePermission(request))
 *         .build()) {
 *
 *     AcpSessionResult result = client.workflow()
 *             .initialize()
 *             .newSession("/workspace")
 *             .model("claude-opus-4-6")
 *             .prompt("Say hello")
 *             .execute();
 * }
 * }</pre>
 */
public final class AcpClient {

    private AcpClient() {
    }

    /**
     * Creates a builder for a synchronous (blocking) ACP client.
     *
     * @param transport the stdio transport to use
     * @return a {@link SyncBuilder} for configuring and building the client
     */
    public static SyncBuilder sync(StdioAcpClientTransport transport) {
        return new SyncBuilder(transport);
    }

    /**
     * Creates a builder for an asynchronous ({@link java.util.concurrent.CompletableFuture}-based) ACP client.
     *
     * @param transport the stdio transport to use
     * @return an {@link AsyncBuilder} for configuring and building the client
     */
    public static AsyncBuilder async(StdioAcpClientTransport transport) {
        return new AsyncBuilder(transport);
    }

    /**
     * Base builder with common configuration for both sync and async clients.
     *
     * <p>
     * Supports two styles of notification handling:
     * <ul>
     * <li><b>Typed handlers</b> via {@link #withNotifications(Consumer)} — register per-type
     * consumers for specific session update types (tool calls, plans, usage, etc.)</li>
     * <li><b>Raw consumer</b> via {@link #onSessionUpdate(Consumer)} — a single
     * consumer that receives all session notifications</li>
     * </ul>
     *
     * <p>
     * Both styles can be combined: typed handlers fire first for their matching types,
     * then the raw consumer fires for every notification.
     *
     * @param <B> the concrete builder subtype (for fluent method chaining)
     */
    public abstract static class AbstractBuilder<B extends AbstractBuilder<B>> {
        final StdioAcpClientTransport transport;
        Duration requestTimeout = Duration.ofSeconds(30);
        Duration promptRequestTimeout = Duration.ZERO;

        NotificationRouter notificationRouter;
        Consumer<SessionNotification> sessionUpdateConsumer;
        Function<RequestPermissionRequest, RequestPermissionResponse> permissionRequestHandler;

        AbstractBuilder(StdioAcpClientTransport transport) {
            this.transport = transport;
        }

        @SuppressWarnings("unchecked")
        protected B self() {
            return (B) this;
        }

        // ===== Timeouts =====

        /**
         * Sets the timeout for individual JSON-RPC requests. Defaults to 30 seconds.
         *
         * @param timeout the request timeout duration
         * @return this builder
         */
        public B withRequestTimeout(Duration timeout) {
            this.requestTimeout = timeout;
            return self();
        }

        /**
         * Sets the timeout for prompt requests. Defaults to {@link Duration#ZERO} (no timeout)
         * since prompts can run for extended periods while the agent processes tool calls.
         *
         * @param timeout the prompt request timeout duration, or {@link Duration#ZERO} for no timeout
         * @return this builder
         */
        public B withPromptRequestTimeout(Duration timeout) {
            this.promptRequestTimeout = timeout;
            return self();
        }

        // ===== Notifications =====

        /**
         * Configures typed notification handlers via a {@link NotificationRouter} sub-builder.
         *
         * <p>
         * Example:
         *
         * <pre>{@code
         * .withNotifications(n -> n
         *     .onAgentMessage(chunk -> System.out.print(text))
         *     .onToolCall(tc -> logger.info(tc.title()))
         *     .onUsage(usage -> logger.info(usage.used())))
         * }</pre>
         *
         * @param configurer a consumer that configures the {@link NotificationRouter}
         * @return this builder
         */
        public B withNotifications(Consumer<NotificationRouter> configurer) {
            this.notificationRouter = new NotificationRouter();
            configurer.accept(this.notificationRouter);
            return self();
        }

        /**
         * Sets a raw consumer for all session update notifications.
         * If typed handlers (via {@link #withNotifications}) are also registered,
         * they fire first for matching types, then this consumer fires for every notification.
         *
         * @param consumer the notification consumer
         * @return this builder
         */
        public B onSessionUpdate(Consumer<SessionNotification> consumer) {
            this.sessionUpdateConsumer = consumer;
            return self();
        }

        // ===== Permission handler =====

        /**
         * Sets the handler for permission requests from the agent.
         * If not set, permissions are auto-accepted with the first allow option.
         *
         * @param handler function that receives the permission request and returns a response
         * @return this builder
         */
        public B withPermission(Function<RequestPermissionRequest, RequestPermissionResponse> handler) {
            this.permissionRequestHandler = handler;
            return self();
        }

        /**
         * Builds the notification consumer from typed handlers and/or the raw consumer.
         */
        protected Consumer<SessionNotification> buildNotificationConsumer() {
            boolean hasRouter = notificationRouter != null && notificationRouter.hasAnyHandler();

            if (hasRouter && sessionUpdateConsumer != null) {
                NotificationRouter router = this.notificationRouter;
                return notification -> {
                    router.accept(notification);
                    sessionUpdateConsumer.accept(notification);
                };
            } else if (hasRouter) {
                return notificationRouter;
            } else {
                return sessionUpdateConsumer;
            }
        }
    }

    /** Builder for configuring and creating an {@link AcpSyncClient}. */
    public static class SyncBuilder extends AbstractBuilder<SyncBuilder> {

        private SyncBuilder(StdioAcpClientTransport transport) {
            super(transport);
        }

        /**
         * Builds and connects the synchronous client.
         *
         * @return a connected {@link AcpSyncClient}
         */
        public AcpSyncClient build() {
            AcpAsyncClient async = new AcpAsyncClient(transport, requestTimeout, promptRequestTimeout,
                    buildNotificationConsumer(), permissionRequestHandler);
            return new AcpSyncClient(async);
        }
    }

    /** Builder for configuring and creating an {@link AcpAsyncClient}. */
    public static class AsyncBuilder extends AbstractBuilder<AsyncBuilder> {

        private AsyncBuilder(StdioAcpClientTransport transport) {
            super(transport);
        }

        /**
         * Builds the asynchronous client. Call {@link AcpAsyncClient#connect()} to start it.
         *
         * @return an {@link AcpAsyncClient} (not yet connected)
         */
        public AcpAsyncClient build() {
            return new AcpAsyncClient(transport, requestTimeout, promptRequestTimeout,
                    buildNotificationConsumer(), permissionRequestHandler);
        }
    }
}
