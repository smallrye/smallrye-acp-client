package io.smallrye.acp.mcp.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.smallrye.acp.mcp.model.McpServer;
import io.smallrye.acp.mcp.model.McpTransport;

/**
 * Import of the layouts accepted by {@code acp mcp add-config-file}.
 */
class McpServerJsonTest {

    @Test
    void mcpServersConfigOfClaudeBobGeminiPi() throws IOException {
        List<McpServer> servers = McpServerJson.parseConfig("""
                {"mcpServers": {
                  "claude": {"type": "http", "url": "https://a/mcp", "headers": {"X": "1"}},
                  "bob":    {"type": "streamable-http", "url": "https://b/mcp"},
                  "gemini": {"httpUrl": "https://c/mcp"},
                  "geminiSse": {"url": "https://c/sse", "type": "sse"},
                  "pi":     {"url": "https://d/mcp", "transport": "streamable-http",
                             "auth": {"type": "bearer", "token": "env:KEY"}},
                  "db":     {"command": "npx", "args": ["-y", "tool"], "env": {"K": "V"}}
                }}
                """, false);
        assertEquals(6, servers.size());
        assertEquals(McpServer.remote("claude", McpTransport.HTTP, "https://a/mcp").header("X", "1").build(),
                servers.get(0));
        assertEquals(McpTransport.HTTP, servers.get(1).transport());
        assertEquals("https://c/mcp", servers.get(2).url());
        assertEquals(McpTransport.SSE, servers.get(3).transport());
        assertEquals("Bearer ${KEY}", servers.get(4).headers().get("Authorization"));
        assertEquals(McpServer.stdio("db", "npx").args(List.of("-y", "tool")).env("K", "V").build(), servers.get(5));
    }

    @Test
    void openCodeConfig() throws IOException {
        List<McpServer> servers = McpServerJson.parseConfig("""
                {"$schema": "https://opencode.ai/config.json",
                 "mcp": {
                   "db": {"type": "local", "command": ["npx", "-y", "tool"], "environment": {"K": "V"}},
                   "remote": {"type": "remote", "url": "https://a/mcp"}
                 }}
                """, false);
        assertEquals(McpServer.stdio("db", "npx").args(List.of("-y", "tool")).env("K", "V").build(), servers.get(0));
        assertEquals(McpTransport.HTTP, servers.get(1).transport());
    }

    @Test
    void codexTomlConfig() throws IOException {
        List<McpServer> servers = McpServerJson.parseConfig("""
                model = "gpt-5"

                [mcp_servers.db]
                command = "npx"
                args = ["-y", "tool"]
                env = { K = "V" }

                [mcp_servers.remote]
                url = "https://a/mcp"
                bearer_token_env_var = "API_KEY"
                http_headers = { X = "1" }
                """, true);
        assertEquals(McpServer.stdio("db", "npx").args(List.of("-y", "tool")).env("K", "V").build(), servers.get(0));
        assertEquals(Map.of("X", "1", "Authorization", "Bearer ${API_KEY}"), servers.get(1).headers());
    }

    @Test
    void acpEntries() throws IOException {
        List<McpServer> servers = McpServerJson.parseConfig("""
                [{"type": "stdio", "name": "quarkit", "command": "jbang", "args": ["mcp@quarkit"],
                  "env": [{"name": "K", "value": "V"}]},
                 {"type": "http", "name": "api", "url": "http://localhost:8080/mcp",
                  "headers": [{"name": "X", "value": "1"}]}]
                """, false);
        assertEquals(McpServer.stdio("quarkit", "jbang").arg("mcp@quarkit").env("K", "V").build(), servers.get(0));
        assertEquals("1", servers.get(1).headers().get("X"));

        List<McpServer> single = McpServerJson.parseConfig(
                "{\"type\":\"stdio\",\"name\":\"quarkit\",\"command\":\"jbang\",\"args\":[\"mcp@quarkit\"]}", false);
        assertEquals("quarkit", single.get(0).name());
    }

    @Test
    void parseFromAndToJsonMcpServer() {
        assertEquals(McpRegistryTest.QUARKIT_HTTP,
                McpServerJson.fromJson("quarkit-http", McpServerJson.toJson(McpRegistryTest.QUARKIT_HTTP)));

        assertEquals(McpRegistryTest.QUARKIT,
                McpServerJson.fromJson("quarkit", McpServerJson.toJson(McpRegistryTest.QUARKIT)));
    }

    @Test
    void rejectsUnknownDocuments() {
        assertThrows(IllegalArgumentException.class, () -> McpServerJson.parseConfig("{\"theme\": \"dark\"}", false));
        assertThrows(IllegalArgumentException.class,
                () -> McpServerJson.parseConfig("{\"mcpServers\": {\"x\": {\"type\": \"ws\", \"url\": \"ws://a\"}}}",
                        false));
    }
}
