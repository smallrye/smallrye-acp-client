package io.smallrye.acp.mcp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.aesh.command.invocation.CommandInvocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.smallrye.acp.mcp.model.McpScope;
import io.smallrye.acp.mcp.model.McpServer;
import io.smallrye.acp.mcp.probe.McpProbe;
import io.smallrye.acp.mcp.registry.McpEnv;

/**
 * Helpers shared by the {@code acp mcp} commands.
 */
final class McpCommandSupport {

    /** Unit separator: repeated -e / -H values must not be split on ',' since they may contain it. */
    static final char NO_SPLIT = '\u001F';

    static final String SCOPE_HELP = "Registry scope: home (~/.acp/mcp.json, default) or local (./.acp/mcp.json of the "
            + "current directory)";
    static final String TIMEOUT_HELP = "Timeout in seconds of each MCP request when checking a server (default: 30)";

    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private McpCommandSupport() {
    }

    static McpScope scope(String value) {
        return value == null || value.isBlank() ? null : McpScope.from(value);
    }

    /** Parses {@code KEY=value} pairs. */
    static Map<String, String> parseEnv(List<String> values) {
        Map<String, String> env = new LinkedHashMap<>();
        if (values != null) {
            for (String v : values) {
                int idx = v.indexOf('=');
                if (idx <= 0) {
                    throw new IllegalArgumentException("Invalid environment variable '" + v + "', expected KEY=value");
                }
                env.put(v.substring(0, idx).trim(), v.substring(idx + 1));
            }
        }
        return env;
    }

    /** Parses {@code Name: value} headers. */
    static Map<String, String> parseHeaders(List<String> values) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (values != null) {
            for (String v : values) {
                int idx = v.indexOf(':');
                if (idx <= 0) {
                    throw new IllegalArgumentException("Invalid header '" + v + "', expected 'Name: value'");
                }
                headers.put(v.substring(0, idx).trim(), v.substring(idx + 1).trim());
            }
        }
        return headers;
    }

    static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= maxLen ? oneLine : oneLine.substring(0, maxLen - 3) + "...";
    }

    static Duration timeout(Integer seconds) {
        return Duration.ofSeconds(seconds == null || seconds <= 0 ? 30 : seconds);
    }

    /** Expands the variables then probes the server. Expansion errors are reported as a failed probe. */
    static McpProbe.Result probe(McpServer server, Duration timeout) {
        McpServer expanded;
        try {
            expanded = McpEnv.expand(server);
        } catch (IllegalArgumentException e) {
            return McpProbe.Result.failure(e.getMessage(), Duration.ZERO, List.of());
        }
        return McpProbe.probe(expanded, timeout, Path.of(System.getProperty("user.dir")));
    }

    /** Prints the JSON-RPC traffic: {@code >>} sent to the server, {@code <<} received from it. */
    static void printTrace(CommandInvocation invocation, List<McpProbe.Message> trace) {
        for (McpProbe.Message message : trace) {
            invocation.println((message.sent() ? ">> " : "<< ") + pretty(message.json()).replace("\n", "\n   "));
        }
    }

    private static String pretty(String json) {
        try {
            return PRETTY.writeValueAsString(PRETTY.readTree(json));
        } catch (Exception e) {
            return json;
        }
    }

    /** One line status of a probe, used in tables. */
    static String status(McpProbe.Result result) {
        if (result.up()) {
            return "up (" + result.tools().size() + " tools)";
        }
        return "down: " + truncate(firstLine(result.error()), 60);
    }

    static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int idx = s.indexOf('\n');
        return idx < 0 ? s : s.substring(0, idx);
    }
}
