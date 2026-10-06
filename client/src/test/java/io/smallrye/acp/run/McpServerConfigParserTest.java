package io.smallrye.acp.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.McpServerHttp;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.McpServerSse;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.McpServerStdio;

class McpServerConfigParserTest {

    @Test
    void parseStdioServer() throws IOException {
        String json = """
                {
                  "type": "stdio",
                  "name": "filesystem",
                  "command": "npx",
                  "args": ["-y", "@anthropic-ai/mcp-filesystem", "/tmp"],
                  "env": [
                    { "name": "DEBUG", "value": "true" }
                  ]
                }
                """;

        List<Object> servers = RunCommand.parseMcpServerConfig(json);

        assertEquals(1, servers.size());
        var stdio = assertInstanceOf(McpServerStdio.class, servers.get(0));
        assertEquals("filesystem", stdio.name());
        assertEquals("npx", stdio.command());
        assertEquals(List.of("-y", "@anthropic-ai/mcp-filesystem", "/tmp"), stdio.args());
        assertEquals(1, stdio.env().size());
        assertEquals("DEBUG", stdio.env().get(0).name());
        assertEquals("true", stdio.env().get(0).value());
        assertEquals("stdio", stdio.type());
    }

    @Test
    void parseSseServer() throws IOException {
        String json = """
                {
                  "type": "sse",
                  "name": "my-sse-server",
                  "url": "http://localhost:3000/sse",
                  "headers": [
                    { "name": "Authorization", "value": "Bearer token123" }
                  ]
                }
                """;

        List<Object> servers = RunCommand.parseMcpServerConfig(json);

        assertEquals(1, servers.size());
        var sse = assertInstanceOf(McpServerSse.class, servers.get(0));
        assertEquals("my-sse-server", sse.name());
        assertEquals("http://localhost:3000/sse", sse.url());
        assertEquals(1, sse.headers().size());
        assertEquals("Authorization", sse.headers().get(0).name());
        assertEquals("Bearer token123", sse.headers().get(0).value());
        assertEquals("sse", sse.type());
    }

    @Test
    void parseHttpServer() throws IOException {
        String json = """
                {
                  "type": "http",
                  "name": "my-api",
                  "url": "http://localhost:8080/mcp",
                  "headers": [
                    { "name": "X-Api-Key", "value": "secret" },
                    { "name": "Accept", "value": "application/json" }
                  ]
                }
                """;

        List<Object> servers = RunCommand.parseMcpServerConfig(json);

        assertEquals(1, servers.size());
        var http = assertInstanceOf(McpServerHttp.class, servers.get(0));
        assertEquals("my-api", http.name());
        assertEquals("http://localhost:8080/mcp", http.url());
        assertEquals(2, http.headers().size());
        assertEquals("X-Api-Key", http.headers().get(0).name());
        assertEquals("Accept", http.headers().get(1).name());
        assertEquals("http", http.type());
    }

    @Test
    void parseMixedArray() throws IOException {
        String json = """
                [
                  { "type": "stdio", "name": "fs", "command": "npx", "args": ["--stdio"] },
                  { "type": "sse", "name": "events", "url": "http://localhost:3000/sse" },
                  { "type": "http", "name": "api", "url": "http://localhost:8080/mcp" }
                ]
                """;

        List<Object> servers = RunCommand.parseMcpServerConfig(json);

        assertEquals(3, servers.size());
        assertInstanceOf(McpServerStdio.class, servers.get(0));
        assertInstanceOf(McpServerSse.class, servers.get(1));
        assertInstanceOf(McpServerHttp.class, servers.get(2));
    }

    @Test
    void parseFromFile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("mcp-servers.json");
        Files.writeString(configFile, """
                [
                  { "type": "stdio", "name": "fs", "command": "/usr/bin/mcp-fs", "args": ["--root", "/home"] },
                  { "type": "http", "name": "remote", "url": "https://api.example.com/mcp", "headers": [] }
                ]
                """);

        List<Object> servers = RunCommand.parseMcpServerConfig(configFile.toString());

        assertEquals(2, servers.size());
        var stdio = assertInstanceOf(McpServerStdio.class, servers.get(0));
        assertEquals("fs", stdio.name());
        assertEquals("/usr/bin/mcp-fs", stdio.command());
        var http = assertInstanceOf(McpServerHttp.class, servers.get(1));
        assertEquals("remote", http.name());
        assertEquals("https://api.example.com/mcp", http.url());
        assertTrue(http.headers().isEmpty());
    }

    @Test
    void defaultsToStdioWhenTypeOmitted() throws IOException {
        String json = """
                { "name": "server", "command": "my-cmd", "args": [] }
                """;

        List<Object> servers = RunCommand.parseMcpServerConfig(json);

        assertEquals(1, servers.size());
        assertInstanceOf(McpServerStdio.class, servers.get(0));
    }

    @Test
    void unknownTypeThrows() {
        String json = """
                { "type": "grpc", "name": "server", "url": "localhost:9090" }
                """;

        var ex = assertThrows(IllegalArgumentException.class,
                () -> RunCommand.parseMcpServerConfig(json));
        assertTrue(ex.getMessage().contains("grpc"));
    }

    @Test
    void missingRequiredFieldThrows() {
        String json = """
                { "type": "stdio", "name": "server" }
                """;

        var ex = assertThrows(IllegalArgumentException.class,
                () -> RunCommand.parseMcpServerConfig(json));
        assertTrue(ex.getMessage().contains("command"));
    }

    @Test
    void missingNameFieldThrows() {
        String json = """
                { "type": "stdio", "command": "my-cmd" }
                """;

        var ex = assertThrows(IllegalArgumentException.class,
                () -> RunCommand.parseMcpServerConfig(json));
        assertTrue(ex.getMessage().contains("name"));
    }

    @Test
    void nonTextualCommandThrows() {
        String json = """
                { "type": "stdio", "name": "server", "command": true }
                """;

        var ex = assertThrows(IllegalArgumentException.class,
                () -> RunCommand.parseMcpServerConfig(json));
        assertTrue(ex.getMessage().contains("command"));
        assertTrue(ex.getMessage().contains("string"));
    }

    @Test
    void nonArrayArgsThrows() {
        String json = """
                { "type": "stdio", "name": "server", "command": "cmd", "args": "not-an-array" }
                """;

        var ex = assertThrows(IllegalArgumentException.class,
                () -> RunCommand.parseMcpServerConfig(json));
        assertTrue(ex.getMessage().contains("args"));
        assertTrue(ex.getMessage().contains("array"));
    }

    @Test
    void nonTextualArgElementThrows() {
        String json = """
                { "type": "stdio", "name": "server", "command": "cmd", "args": [123] }
                """;

        var ex = assertThrows(IllegalArgumentException.class,
                () -> RunCommand.parseMcpServerConfig(json));
        assertTrue(ex.getMessage().contains("args"));
        assertTrue(ex.getMessage().contains("string"));
    }

    @Test
    void nonObjectEntryThrows() {
        String json = """
                [ "not-an-object" ]
                """;

        var ex = assertThrows(IllegalArgumentException.class,
                () -> RunCommand.parseMcpServerConfig(json));
        assertTrue(ex.getMessage().contains("JSON object"));
    }

    @Test
    void stdioWithOptionalFieldsOmitted() throws IOException {
        String json = """
                { "type": "stdio", "name": "minimal", "command": "my-server" }
                """;

        List<Object> servers = RunCommand.parseMcpServerConfig(json);

        var stdio = assertInstanceOf(McpServerStdio.class, servers.get(0));
        assertEquals("minimal", stdio.name());
        assertEquals("my-server", stdio.command());
        assertTrue(stdio.args().isEmpty());
        assertTrue(stdio.env().isEmpty());
    }

    @Test
    void httpWithNoHeaders() throws IOException {
        String json = """
                { "type": "http", "name": "bare", "url": "http://localhost:8080" }
                """;

        List<Object> servers = RunCommand.parseMcpServerConfig(json);

        var http = assertInstanceOf(McpServerHttp.class, servers.get(0));
        assertTrue(http.headers().isEmpty());
    }

    @Test
    void missingUrlForHttpThrows() {
        String json = """
                { "type": "http", "name": "server" }
                """;

        var ex = assertThrows(IllegalArgumentException.class,
                () -> RunCommand.parseMcpServerConfig(json));
        assertTrue(ex.getMessage().contains("url"));
    }

    @Test
    void missingUrlForSseThrows() {
        String json = """
                { "type": "sse", "name": "server" }
                """;

        var ex = assertThrows(IllegalArgumentException.class,
                () -> RunCommand.parseMcpServerConfig(json));
        assertTrue(ex.getMessage().contains("url"));
    }

    @Test
    void nonTextualUrlThrows() {
        String json = """
                { "type": "http", "name": "server", "url": 8080 }
                """;

        var ex = assertThrows(IllegalArgumentException.class,
                () -> RunCommand.parseMcpServerConfig(json));
        assertTrue(ex.getMessage().contains("url"));
        assertTrue(ex.getMessage().contains("string"));
    }

    @Test
    void nonArrayEnvThrows() {
        String json = """
                { "type": "stdio", "name": "server", "command": "cmd", "env": "not-array" }
                """;

        var ex = assertThrows(IllegalArgumentException.class,
                () -> RunCommand.parseMcpServerConfig(json));
        assertTrue(ex.getMessage().contains("env"));
        assertTrue(ex.getMessage().contains("array"));
    }

    @Test
    void envEntryMissingNameThrows() {
        String json = """
                { "type": "stdio", "name": "server", "command": "cmd", "env": [{ "value": "v" }] }
                """;

        var ex = assertThrows(IllegalArgumentException.class,
                () -> RunCommand.parseMcpServerConfig(json));
        assertTrue(ex.getMessage().contains("name"));
    }

    @Test
    void envEntryMissingValueThrows() {
        String json = """
                { "type": "stdio", "name": "server", "command": "cmd", "env": [{ "name": "K" }] }
                """;

        var ex = assertThrows(IllegalArgumentException.class,
                () -> RunCommand.parseMcpServerConfig(json));
        assertTrue(ex.getMessage().contains("value"));
    }

    @Test
    void nonArrayHeadersThrows() {
        String json = """
                { "type": "http", "name": "server", "url": "http://localhost", "headers": "bad" }
                """;

        var ex = assertThrows(IllegalArgumentException.class,
                () -> RunCommand.parseMcpServerConfig(json));
        assertTrue(ex.getMessage().contains("headers"));
        assertTrue(ex.getMessage().contains("array"));
    }

    @Test
    void headerEntryMissingNameThrows() {
        String json = """
                { "type": "http", "name": "server", "url": "http://localhost", "headers": [{ "value": "v" }] }
                """;

        var ex = assertThrows(IllegalArgumentException.class,
                () -> RunCommand.parseMcpServerConfig(json));
        assertTrue(ex.getMessage().contains("name"));
    }

    @Test
    void malformedJsonThrows() {
        String json = "{ not valid json }";

        assertThrows(IOException.class,
                () -> RunCommand.parseMcpServerConfig(json));
    }

    @Test
    void nonExistentFileThrows() {
        assertThrows(IOException.class,
                () -> RunCommand.parseMcpServerConfig("/nonexistent/path/config.json"));
    }

    @Test
    void emptyArrayReturnsEmptyList() throws IOException {
        String json = "[]";

        List<Object> servers = RunCommand.parseMcpServerConfig(json);

        assertTrue(servers.isEmpty());
    }

    @Test
    void stdioWithMultipleEnvVariables() throws IOException {
        String json = """
                {
                  "type": "stdio", "name": "server", "command": "cmd",
                  "env": [
                    { "name": "KEY1", "value": "val1" },
                    { "name": "KEY2", "value": "val2" },
                    { "name": "KEY3", "value": "val3" }
                  ]
                }
                """;

        List<Object> servers = RunCommand.parseMcpServerConfig(json);

        var stdio = assertInstanceOf(McpServerStdio.class, servers.get(0));
        assertEquals(3, stdio.env().size());
        assertEquals("KEY1", stdio.env().get(0).name());
        assertEquals("KEY2", stdio.env().get(1).name());
        assertEquals("KEY3", stdio.env().get(2).name());
    }

    @Test
    void sseWithNoHeaders() throws IOException {
        String json = """
                { "type": "sse", "name": "bare", "url": "http://localhost:3000/sse" }
                """;

        List<Object> servers = RunCommand.parseMcpServerConfig(json);

        var sse = assertInstanceOf(McpServerSse.class, servers.get(0));
        assertTrue(sse.headers().isEmpty());
    }
}
