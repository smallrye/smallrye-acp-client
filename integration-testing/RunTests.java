///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS io.smallrye.ai:acp-java-core:0.2.1-SNAPSHOT
//DEPS io.smallrye.ai:acp-java-schema:0.2.1-SNAPSHOT
//DEPS io.smallrye.ai:acp-java-registry:0.2.1-SNAPSHOT
//DEPS org.jboss.logging:jboss-logging:3.6.3.Final
//SOURCES AcpTestConfig.java
//SOURCES check/Check.java
//SOURCES check/InitializeCheck.java
//SOURCES check/SessionCheck.java
//SOURCES check/PromptCheck.java
//SOURCES check/WorkflowCheck.java

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JBang-based integration test runner for ACP agents.
 *
 * <p>Resolves an agent from the ACP registry (or a direct binary path)
 * and runs a suite of checks against it.
 *
 * <p>Usage:
 * <pre>
 * # First, install the Smallrye ACP jars locally
 * mvn install -DskipTests
 *
 * # Run all checks against a registry-installed agent
 * jbang integration-testing/RunTests.java --agent bob
 *
 * # Run with a custom prompt
 * jbang integration-testing/RunTests.java --agent bob --prompt "What is 2+2?"
 *
 * # Run a specific check
 * jbang integration-testing/RunTests.java --agent bob --check initialize
 *
 * # Run with direct binary path
 * jbang integration-testing/RunTests.java --agent-binary /path/to/agent --agent-args acp
 * </pre>
 */
public class RunTests {

    private static final Map<String, Check> ALL_CHECKS = new LinkedHashMap<>();

    static {
        ALL_CHECKS.put("initialize", new InitializeCheck());
        ALL_CHECKS.put("session-lifecycle", new SessionCheck());
        ALL_CHECKS.put("prompt", new PromptCheck());
        ALL_CHECKS.put("workflow", new WorkflowCheck());
    }

    public static void main(String[] args) {
        AcpTestConfig config = AcpTestConfig.fromArgs(args);
        List<String> selectedChecks = parseCheckFilter(args);

        System.out.println("=== ACP Integration Tests ===");
        System.out.printf("Agent: %s%n", config.agentId());
        System.out.printf("Prompt: %s%n", config.prompt());
        System.out.println();

        List<Check.CheckResult> results = new ArrayList<>();

        Map<String, Check> checksToRun = selectedChecks.isEmpty()
                ? ALL_CHECKS
                : filterChecks(selectedChecks);

        for (var entry : checksToRun.entrySet()) {
            System.out.printf("Running: %s ...%n", entry.getKey());
            try {
                Check.CheckResult result = entry.getValue().run(config);
                results.add(result);
                result.print();
            } catch (Exception e) {
                var result = Check.CheckResult.fail(entry.getKey(), e.getMessage());
                results.add(result);
                result.print();
            }
            System.out.println();
        }

        long passed = results.stream().filter(Check.CheckResult::passed).count();
        long failed = results.size() - passed;

        System.out.println("=== Results ===");
        System.out.printf("Passed: %d, Failed: %d, Total: %d%n", passed, failed, results.size());

        if (failed > 0) {
            System.out.println();
            System.out.println("Failed checks:");
            results.stream()
                    .filter(r -> !r.passed())
                    .forEach(r -> System.out.printf("  - %s: %s%n", r.name(), r.detail()));
            System.exit(1);
        }
    }

    private static List<String> parseCheckFilter(String[] args) {
        List<String> checks = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--check".equals(args[i]) && i + 1 < args.length) {
                checks.add(args[++i]);
            }
        }
        return checks;
    }

    private static Map<String, Check> filterChecks(List<String> selected) {
        Map<String, Check> filtered = new LinkedHashMap<>();
        for (String name : selected) {
            Check check = ALL_CHECKS.get(name);
            if (check != null) {
                filtered.put(name, check);
            } else {
                System.err.printf("Unknown check: %s (available: %s)%n",
                        name, String.join(", ", ALL_CHECKS.keySet()));
                System.exit(1);
            }
        }
        return filtered;
    }
}
