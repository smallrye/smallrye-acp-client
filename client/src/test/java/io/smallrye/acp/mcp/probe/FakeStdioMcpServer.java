package io.smallrye.acp.mcp.probe;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A tiny stdio MCP server used by {@link McpProbeTest}: answers initialize and tools/list.
 */
public class FakeStdioMcpServer {

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        System.out.println("banner line that is not JSON");
        System.err.println("starting fake server, env FOO=" + System.getenv("FOO"));
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            JsonNode msg = mapper.readTree(line);
            if (!msg.has("id")) {
                continue;
            }
            String id = msg.get("id").toString();
            String result = switch (msg.get("method").asText()) {
                case "initialize" -> "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}},"
                        + "\"serverInfo\":{\"name\":\"fake-stdio\",\"version\":\"" + System.getenv("FOO") + "\"}}";
                case "tools/list" -> "{\"tools\":[{\"name\":\"echo\",\"description\":\"Echo a message\"}]}";
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
