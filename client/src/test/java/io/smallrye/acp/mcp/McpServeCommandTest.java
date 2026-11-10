package io.smallrye.acp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.smallrye.acp.mcp.model.McpServeSpec;

class McpServeCommandTest {

    static final McpServeSpec SERVE = new McpServeSpec("jbang", List.of("mcp-http@quarkit"),
            Map.of("QUARKIT_ALLOWED_DIRS", "."));

    @Test
    void registeredEnvIsUsedWithoutOverrides() {
        assertEquals(Map.of("QUARKIT_ALLOWED_DIRS", "."), McpServeCommand.environment(SERVE, Map.of()));
    }

    @Test
    void cliEnvOverridesAndExtendsTheRegisteredOne() {
        var env = McpServeCommand.environment(SERVE, McpCommandSupport.parseEnv(List.of(
                "QUARKIT_ALLOWED_DIRS=./applications,/tmp/projects",
                "QUARKIT_REFERENCES_PATH=./refs")));
        assertEquals(Map.of("QUARKIT_ALLOWED_DIRS", "./applications,/tmp/projects",
                "QUARKIT_REFERENCES_PATH", "./refs"), env);
    }

    @Test
    void cliEnvReferencesAreExpanded() {
        var env = McpServeCommand.environment(SERVE,
                McpCommandSupport.parseEnv(List.of("DIRS=${ACP_TEST_UNSET_VAR:-/tmp/default}")));
        assertEquals("/tmp/default", env.get("DIRS"));
    }

    @Test
    void invalidCliEnvIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> McpCommandSupport.parseEnv(List.of("NO_VALUE")));
    }
}
