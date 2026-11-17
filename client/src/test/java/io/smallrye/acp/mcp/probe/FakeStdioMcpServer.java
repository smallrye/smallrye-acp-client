package io.smallrye.acp.mcp.probe;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A tiny stdio MCP server used by {@link McpProbeTest} and {@link McpClientTest}: answers initialize, tools/list and
 * tools/call (tool echo). With {@code STRICT=true} it rejects the requests sent before initialize.
 */
public class FakeStdioMcpServer {

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        System.out.println("banner line that is not JSON");
        System.err.println("starting fake server, env FOO=" + System.getenv("FOO"));
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        boolean strict = "true".equals(System.getenv("STRICT"));
        boolean initialized = false;
        String line;
        while ((line = in.readLine()) != null) {
            JsonNode msg = mapper.readTree(line);
            if (!msg.has("id")) {
                continue;
            }
            String id = msg.get("id").toString();
            String method = msg.get("method").asText();
            if (strict && !initialized && !"initialize".equals(method)) {
                System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"error\":{\"code\":-32600,\"message\":\"Server not initialized\"}}");
                System.out.flush();
                continue;
            }
            initialized |= "initialize".equals(method);
            String result = switch (method) {
                case "initialize" -> "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}},"
                        + "\"serverInfo\":{\"name\":\"fake-stdio\",\"version\":\"" + System.getenv("FOO") + "\"}}";
                case "tools/list" -> "{\"tools\":[{\"name\":\"echo\",\"description\":\"Echo a message\"}]}";
                case "tools/call" -> "{\"content\":[{\"type\":\"text\",\"text\":"
                        + mapper.writeValueAsString("echo: " + msg.path("params").path("arguments").path("message")
                                .asText())
                        + "}]}";
                default -> null;
            };
            if (result == null) {
                System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}");
            } else {
                // a server log notification before the response, to check it is skipped
                System.out.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{}}");
                System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
            }
            System.out.flush();
        }
    }
}
