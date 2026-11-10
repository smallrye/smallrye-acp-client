package io.smallrye.acp.mcp.registry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.List;

import io.smallrye.acp.mcp.model.McpServer;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.EnvVariable;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.HttpHeader;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.McpCapabilities;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.McpServerHttp;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.McpServerSse;
import io.smallrye.agentclientprotocol.sdk.spec.schema.v1.McpServerStdio;

/**
 * Bridges registry entries and the ACP {@code session/new} {@code mcpServers} payload.
 */
public final class AcpMcpServers {

    private AcpMcpServers() {
    }

    /** Converts an (already expanded) server into the ACP schema type sent to the agent. */
    public static Object toAcp(McpServer server) {
        return switch (server.transport()) {
            case STDIO -> new McpServerStdio(server.args(), server.command(),
                    server.env().entrySet().stream().map(e -> new EnvVariable(e.getKey(), e.getValue())).toList(),
                    server.name());
            case HTTP -> new McpServerHttp(headers(server), server.name(), server.url());
            case SSE -> new McpServerSse(headers(server), server.name(), server.url());
        };
    }

    private static List<HttpHeader> headers(McpServer server) {
        return server.headers().entrySet().stream().map(e -> new HttpHeader(e.getKey(), e.getValue())).toList();
    }

    /**
     * Checks that the agent can reach the given ACP servers: stdio is mandatory for every agent, http and sse must
     * be advertised in {@code agentCapabilities.mcpCapabilities}.
     *
     * @throws IllegalStateException listing the servers the agent cannot use
     */
    public static void checkCapabilities(List<Object> servers, McpCapabilities capabilities, String agentName) {
        boolean http = capabilities != null && Boolean.TRUE.equals(capabilities.http());
        boolean sse = capabilities != null && Boolean.TRUE.equals(capabilities.sse());
        List<String> unsupported = servers.stream()
                .map(s -> switch (s) {
                    case McpServerHttp h when !http -> h.name() + " (http)";
                    case McpServerSse h when !sse -> h.name() + " (sse)";
                    default -> null;
                })
                .filter(s -> s != null)
                .toList();
        if (!unsupported.isEmpty()) {
            throw new IllegalStateException("The agent " + agentName + " does not support the transport of the MCP "
                    + "server(s): " + String.join(", ", unsupported)
                    + ". Its mcpCapabilities are: http=" + http + ", sse=" + sse
                    + ". Use a stdio definition instead.");
        }
    }

    /** {@code true} when a TCP connection to the host/port of {@code url} can be opened. */
    public static boolean isReachable(String url, int timeoutMillis) {
        try {
            URI uri = URI.create(url);
            int port = uri.getPort() != -1 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), port), timeoutMillis);
                return true;
            }
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }
}
