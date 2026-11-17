package io.smallrye.acp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import io.smallrye.acp.mcp.model.McpScope;
import io.smallrye.acp.mcp.model.McpServer;
import io.smallrye.acp.mcp.probe.FakeStdioMcpServer;
import io.smallrye.acp.mcp.registry.McpRegistry;

/**
 * Checks that the outcome of {@code acp mcp call} is propagated to the process exit status, so that scripts can detect
 * a failure (usage error, unknown server, tool result with {@code isError=true}).
 */
@QuarkusMainTest
class McpCallExitCodeTest {

    @TempDir
    Path tmp;

    private String userHome;
    private String userDir;

    @BeforeEach
    void isolateRegistry() throws Exception {
        // McpRegistry resolves ~/.acp/mcp.json and ./.acp/mcp.json from these properties
        userHome = System.getProperty("user.home");
        userDir = System.getProperty("user.dir");
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path work = Files.createDirectories(tmp.resolve("work"));
        String java = ProcessHandle.current().info().command().orElse("java");
        new McpRegistry(home, work).put(McpScope.HOME, McpServer.stdio("fake", java)
                .args(List.of("-cp", System.getProperty("java.class.path"), FakeStdioMcpServer.class.getName()))
                .build());
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", work.toString());
    }

    @AfterEach
    void restore() {
        System.setProperty("user.home", userHome);
        System.setProperty("user.dir", userDir);
    }

    @Test
    void successfulToolCallExitsWithZero(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("mcp", "call", "fake", "echo", "-p", "message=hi");
        assertEquals(0, result.exitCode(), result.getOutput());
        assertTrue(result.getOutput().contains("echo: hi"), result.getOutput());
    }

    @Test
    void toolResultWithIsErrorExitsWithNonZero(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("mcp", "call", "fake", "fail");
        assertNotEquals(0, result.exitCode(), result.getOutput());
        assertTrue(result.getOutput().contains("Tool 'fail' returned an error"), result.getOutput());
    }

    @Test
    void toolResultWithIsErrorExitsWithNonZeroInJsonMode(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("mcp", "call", "fake", "fail", "--json");
        assertNotEquals(0, result.exitCode(), result.getOutput());
        assertTrue(result.getOutput().contains("\"isError\""), result.getOutput());
    }

    @Test
    void unknownServerExitsWithNonZero(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("mcp", "call", "does-not-exist", "echo");
        assertNotEquals(0, result.exitCode(), result.getOutput());
    }

    @Test
    void usageErrorExitsWithNonZero(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("mcp", "call", "fake");
        assertNotEquals(0, result.exitCode(), result.getOutput());
        assertTrue(result.getOutput().contains("Usage: acp mcp call"), result.getOutput());
    }
}
