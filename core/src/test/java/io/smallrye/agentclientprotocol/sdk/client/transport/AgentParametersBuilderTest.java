package io.smallrye.agentclientprotocol.sdk.client.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class AgentParametersBuilderTest {

    @Test
    void builderSetsCommand() {
        var params = AgentParameters.builder("myCmd").build();
        assertEquals("myCmd", params.getCommand());
    }

    @Test
    void builderWithSingleArg() {
        var params = AgentParameters.builder("cmd")
                .arg("first")
                .build();
        assertEquals(List.of("first"), params.getArgs());
    }

    @Test
    void builderWithSeveralArgs() {
        var params = AgentParameters.builder("cmd")
                .args("a", "b", "c")
                .build();
        assertEquals(List.of("a", "b", "c"), params.getArgs());
    }

    @Test
    void builderWithTrimArgs() {
        var params = AgentParameters.builder("cmd")
                .args(Arrays.asList("  hello ", "", "  ", "world"))
                .build();
        assertEquals(List.of("hello", "world"), params.getArgs());
    }

    @Test
    void builderWithNullArg() {
        var params = AgentParameters.builder("cmd")
                .args(Arrays.asList("ok", null, "fine"))
                .build();
        assertEquals(List.of("ok", "fine"), params.getArgs());
    }

    @Test
    void builderWithNullListArgs() {
        var params = AgentParameters.builder("cmd")
                .args((List<String>) null)
                .build();
        assertTrue(params.getArgs().isEmpty());
    }

    @Test
    void builderWithEmptyListArgs() {
        var params = AgentParameters.builder("cmd")
                .args(Collections.emptyList())
                .build();
        assertTrue(params.getArgs().isEmpty());
    }

    @Test
    void builderChainsArgAndListArgs() {
        var params = AgentParameters.builder("cmd")
                .arg("first")
                .args(List.of("second", "third"))
                .arg("fourth")
                .build();
        assertEquals(List.of("first", "second", "third", "fourth"), params.getArgs());
    }

    @Test
    void builderWithEnvVar() {
        var params = AgentParameters.builder("cmd")
                .addEnvVar("KEY", "value")
                .build();
        assertEquals("value", params.getEnv().get("KEY"));
    }

    @Test
    void defaultEnvVarsAreInherited() {
        var params = AgentParameters.builder("cmd").build();
        assertNotNull(params.getEnv());
        assertTrue(params.getEnv().containsKey("PATH"));
    }

    @Test
    void customEnvVarOverridesDefault() {
        var params = AgentParameters.builder("cmd")
                .addEnvVar("PATH", "/custom/path")
                .build();
        assertEquals("/custom/path", params.getEnv().get("PATH"));
    }

    @Test
    void noArgsProducesEmptyList() {
        var params = AgentParameters.builder("cmd").build();
        assertTrue(params.getArgs().isEmpty());
    }
}
