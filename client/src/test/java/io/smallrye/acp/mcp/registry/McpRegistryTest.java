package io.smallrye.acp.mcp.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.acp.mcp.model.McpScope;
import io.smallrye.acp.mcp.model.McpServeSpec;
import io.smallrye.acp.mcp.model.McpServer;
import io.smallrye.acp.mcp.model.McpTransport;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.McpCapabilities;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.McpServerHttp;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.McpServerStdio;

class McpRegistryTest {

    @TempDir
    Path tmp;
    Path home;
    Path project;
    McpRegistry registry;

    static final McpServer QUARKIT = McpServer.stdio("quarkit", "jbang").arg("mcp@quarkit").env("K", "V").build();
    static final McpServer QUARKIT_HTTP = McpServer.remote("quarkit-http", McpTransport.HTTP,
            "http://localhost:8080/mcp")
            .header("Authorization", "Bearer ${TOKEN}")
            .serve(new McpServeSpec("jbang", List.of("-Dquarkus.mcp.server.stdio.enabled=false", "mcp@quarkit"),
                    Map.of()))
            .build();

    @BeforeEach
    void setUp() throws IOException {
        home = Files.createDirectories(tmp.resolve("home"));
        project = Files.createDirectories(tmp.resolve("project"));
        registry = new McpRegistry(home, project);
    }

    @Test
    void putListFindRemove() throws IOException {
        registry.put(McpScope.HOME, QUARKIT);
        registry.put(McpScope.HOME, QUARKIT_HTTP);

        assertEquals(home.resolve(".acp/mcp.json"), registry.file(McpScope.HOME));
        var entries = registry.list(null);
        assertEquals(List.of("quarkit", "quarkit-http"), entries.stream().map(McpRegistry.Entry::name).toList());
        assertEquals(QUARKIT, entries.get(0).server());
        assertEquals(QUARKIT_HTTP, entries.get(1).server());

        assertTrue(registry.remove(McpScope.HOME, "quarkit"));
        assertFalse(registry.remove(McpScope.HOME, "quarkit"));
        assertTrue(registry.find("quarkit").isEmpty());
    }

    @Test
    void fileLayout() throws IOException {
        registry.put(McpScope.HOME, QUARKIT_HTTP);
        JsonNode entry = new ObjectMapper().readTree(home.resolve(".acp/mcp.json").toFile())
                .at("/mcpServers/quarkit-http");
        assertEquals("http", entry.get("type").asText());
        assertEquals("http://localhost:8080/mcp", entry.get("url").asText());
        assertEquals("Bearer ${TOKEN}", entry.at("/headers/Authorization").asText());
        assertEquals("jbang", entry.at("/serve/command").asText());
        assertEquals("mcp@quarkit", entry.at("/serve/args/1").asText());
    }

    @Test
    void localOverridesHome() throws IOException {
        registry.put(McpScope.HOME, QUARKIT);
        McpServer local = McpServer.stdio("quarkit", "java").arg("-jar").arg("mcp.jar").build();
        registry.put(McpScope.LOCAL, local);

        assertEquals(local, registry.require("quarkit"));
        var entries = registry.list(null);
        assertEquals(2, entries.size());
        assertTrue(entries.get(0).shadowed(), "home entry is shadowed");
        assertEquals(McpScope.LOCAL, entries.get(1).scope());
        assertEquals(1, registry.list(McpScope.HOME).size());
        assertFalse(registry.list(McpScope.HOME).get(0).shadowed());
    }

    @Test
    void unknownAndInvalidEntries() throws IOException {
        Files.createDirectories(home.resolve(".acp"));
        Files.writeString(home.resolve(".acp/mcp.json"), """
                {"other": true, "mcpServers": {"bad": {"type": "stdio"}, "ok": {"command": "x"}}}
                """);
        var entries = registry.list(null);
        assertNull(entries.get(0).server());
        assertTrue(entries.get(0).error().contains("requires a command"));
        assertThrows(IllegalArgumentException.class, () -> registry.require("bad"));
        var unknown = assertThrows(IllegalArgumentException.class, () -> registry.require("nope"));
        assertTrue(unknown.getMessage().contains("Registered: bad, ok"), unknown.getMessage());

        registry.put(McpScope.HOME, QUARKIT);
        assertTrue(Files.readString(home.resolve(".acp/mcp.json")).contains("\"other\": true"),
                "unknown keys are preserved");
    }

    @Test
    void toAcpAndCapabilities() {
        var stdio = assertInstanceOf(McpServerStdio.class, AcpMcpServers.toAcp(QUARKIT));
        assertEquals("jbang", stdio.command());
        assertEquals("K", stdio.env().get(0).name());
        var http = assertInstanceOf(McpServerHttp.class, AcpMcpServers.toAcp(QUARKIT_HTTP));
        assertEquals("http://localhost:8080/mcp", http.url());

        List<Object> servers = List.of(stdio, http);
        AcpMcpServers.checkCapabilities(servers, new McpCapabilities(null, true, false), "a");
        var e = assertThrows(IllegalStateException.class,
                () -> AcpMcpServers.checkCapabilities(servers, new McpCapabilities(null, false, false), "a"));
        assertTrue(e.getMessage().contains("quarkit-http (http)"), e.getMessage());
        AcpMcpServers.checkCapabilities(List.of(stdio), null, "a");
    }

    @Test
    void envExpansion() {
        Map<String, String> env = Map.of("TOKEN", "secret");
        McpServer expanded = McpEnv.expand(QUARKIT_HTTP, env::get);
        assertEquals("Bearer secret", expanded.headers().get("Authorization"));
        assertEquals("a-dflt-b", McpEnv.expand("a-${X:-dflt}-b", env::get));
        assertEquals("$HOME stays", McpEnv.expand("$HOME stays", env::get));
        var e = assertThrows(IllegalArgumentException.class, () -> McpEnv.expand("${MISSING}", env::get));
        assertTrue(e.getMessage().contains("MISSING"));
    }
}
