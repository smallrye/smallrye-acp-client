package io.smallrye.agentclientprotocol.sdk.test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import io.smallrye.agentclientprotocol.sdk.client.AcpAsyncClient;
import io.smallrye.agentclientprotocol.sdk.client.AcpClient;
import io.smallrye.agentclientprotocol.sdk.client.transport.AgentParameters;
import io.smallrye.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;

/**
 * JUnit 5 extension for integration tests against a real ACP agent binary.
 *
 * <p>
 * Creates the transport and client in {@code beforeAll}; tears them down
 * in {@code afterAll}. Call {@link #assumeAvailable()} at the start of each
 * test to skip gracefully when the agent is not installed.
 *
 * <p>
 * Example:
 *
 * <pre>
 * {@code
 * &#64;RegisterExtension
 * static final AcpAgentProcess bob = AcpAgentProcess.command("bob", "acp");
 *
 * @Test
 * void handshake() {
 *     bob.assumeAvailable();
 *     bob.client().connect().join();
 *     InitializeResponse init = bob.client().initialize().join();
 *     assertNotNull(init.agentInfo());
 * }
 * }
 * </pre>
 */
public class AcpAgentProcess implements BeforeAllCallback, AfterEachCallback, AfterAllCallback {

    private final String[] command;
    private Duration requestTimeout = Duration.ofSeconds(30);
    private StdioAcpClientTransport transport;
    private AcpAsyncClient client;

    private AcpAgentProcess(String... command) {
        this.command = command;
    }

    /**
     * Creates an extension for the given agent command.
     *
     * @param command the executable and its arguments (e.g. {@code "bob", "acp"})
     * @return a new extension instance
     */
    public static AcpAgentProcess command(String... command) {
        return new AcpAgentProcess(command);
    }

    /**
     * Sets the request timeout for the pre-built client. Defaults to 30 seconds.
     *
     * @param timeout the request timeout
     * @return this extension for chaining
     */
    public AcpAgentProcess withRequestTimeout(Duration timeout) {
        this.requestTimeout = timeout;
        return this;
    }

    /**
     * Skips the current test if the agent command is not available on the system PATH.
     * Uses JUnit 5 {@link Assumptions#assumeTrue} so the test is marked as skipped, not failed.
     */
    public void assumeAvailable() {
        Assumptions.assumeTrue(isCommandAvailable(),
                "Agent binary not found on PATH: " + command[0]);
    }

    /**
     * Returns the pre-built async client. Call {@code connect()} and
     * {@code initialize()} in the test method.
     *
     * @return the async client
     */
    public AcpAsyncClient client() {
        return client;
    }

    /**
     * Returns the underlying stdio transport for direct access (e.g. raw listeners).
     *
     * @return the transport
     */
    public StdioAcpClientTransport transport() {
        return transport;
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        buildTransportAndClient();
    }

    private void buildTransportAndClient() {
        var builder = AgentParameters.builder(command[0]);
        for (int i = 1; i < command.length; i++) {
            builder.arg(command[i]);
        }
        transport = new StdioAcpClientTransport(builder.build());
        client = AcpClient.async(transport)
                .withRequestTimeout(requestTimeout)
                .build();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        shutdownTransport();
        buildTransportAndClient();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        shutdownTransport();
    }

    private void shutdownTransport() {
        if (client != null) {
            try {
                client.closeGracefully().get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (transport != null) {
                    transport.closeGracefully();
                }
            }
        }
    }

    private boolean isCommandAvailable() {
        try {
            ProcessBuilder pb;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb = new ProcessBuilder("where", command[0]);
            } else {
                pb = new ProcessBuilder("which", command[0]);
            }
            Process p = pb.start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
