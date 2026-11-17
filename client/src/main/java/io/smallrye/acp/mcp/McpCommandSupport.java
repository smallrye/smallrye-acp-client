package io.smallrye.acp.mcp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.aesh.command.invocation.CommandInvocation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.smallrye.acp.mcp.model.McpScope;
import io.smallrye.acp.mcp.model.McpServer;
import io.smallrye.acp.mcp.model.McpTransport;
import io.smallrye.acp.mcp.probe.McpClient;
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

    static final String REQUEST_TIMEOUT_HELP = "Timeout in seconds of each MCP request (default: 30)";
    static final String VERBOSE_HELP = "Print the JSON-RPC requests/responses exchanged with the server";
    static final String LEGACY_HELP = "Perform the initialize handshake (protocol " + McpProbe.PROTOCOL_VERSION
            + ") instead of sending stateless requests (protocol " + McpClient.STATELESS_PROTOCOL_VERSION
            + "). Used automatically when the server rejects a stateless request";

    static final ObjectMapper MAPPER = new ObjectMapper();
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

    static String pretty(String json) {
        try {
            return PRETTY.writeValueAsString(PRETTY.readTree(json));
        } catch (Exception e) {
            return json;
        }
    }

    static String pretty(JsonNode node) {
        try {
            return PRETTY.writeValueAsString(node);
        } catch (Exception e) {
            return String.valueOf(node);
        }
    }

    /** Expands the variables of the server and connects to it (starting the process of a stdio server). */
    static McpClient connect(McpServer server, Integer timeoutSeconds, boolean legacy) throws Exception {
        return McpClient.open(McpEnv.expand(server), timeout(timeoutSeconds), Path.of(System.getProperty("user.dir")),
                legacy);
    }

    /** {@code protocol 2026-07-28 (stateless)} or {@code protocol 2025-06-18 (initialize) - server x 1.0}. */
    static String protocol(McpClient client) {
        if (client.stateless()) {
            return "protocol " + client.protocolVersion() + " (stateless)";
        }
        String server = client.serverName() == null ? ""
                : " - server " + client.serverName()
                        + (client.serverVersion() != null ? " " + client.serverVersion() : "");
        return "protocol " + client.protocolVersion() + " (initialize)" + server;
    }

    /** Reports a failed exchange with the server: the trace when verbose, the error and how to fix it. */
    static org.aesh.command.CommandResult failure(CommandInvocation invocation, McpServer server, String id,
            McpClient client, boolean verbose, Exception e) {
        if (verbose && client != null) {
            printTrace(invocation, client.trace());
            invocation.println("");
        }
        boolean refused = e instanceof java.net.ConnectException || e.getCause() instanceof java.net.ConnectException;
        String message = refused ? "connection refused (" + server.url() + ")"
                : e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        invocation.println("Error: " + message);
        if (refused && server.transport() != McpTransport.STDIO) {
            invocation.println(server.serve() != null ? "Start it first with: acp mcp serve " + id
                    : "Check that the server is running at " + server.url());
        }
        return org.aesh.command.CommandResult.FAILURE;
    }

    static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // best effort
            }
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
