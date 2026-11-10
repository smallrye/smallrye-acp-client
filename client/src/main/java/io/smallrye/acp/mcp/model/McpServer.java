package io.smallrye.acp.mcp.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent-agnostic definition of an MCP server, as stored in the acp registry ({@code .acp/mcp.json}) and sent to the
 * agent in the ACP {@code session/new} request.
 *
 * <ul>
 * <li>{@link McpTransport#STDIO}: the <b>agent</b> starts {@code command args} for the duration of the session.</li>
 * <li>{@link McpTransport#HTTP} / {@link McpTransport#SSE}: the agent connects to {@code url}; the server must already
 * be running. When {@code serve} is set, {@code acp mcp serve <id>} starts it locally.</li>
 * </ul>
 *
 * @param name server id
 * @param transport how the agent reaches the server
 * @param command executable of a stdio server, {@code null} otherwise
 * @param args arguments of a stdio server (never {@code null})
 * @param env environment variables of a stdio server (never {@code null})
 * @param url endpoint of a remote server, {@code null} for stdio
 * @param headers HTTP headers sent to a remote server (never {@code null})
 * @param serve how to start a remote server locally, may be {@code null}
 */
public record McpServer(
        String name,
        McpTransport transport,
        String command,
        List<String> args,
        Map<String, String> env,
        String url,
        Map<String, String> headers,
        McpServeSpec serve) {

    public McpServer {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP server name must not be empty");
        }
        Objects.requireNonNull(transport, "transport");
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(env));
        headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        if (transport == McpTransport.STDIO) {
            if (command == null || command.isBlank()) {
                throw new IllegalArgumentException("The stdio MCP server '" + name + "' requires a command");
            }
            if (serve != null) {
                throw new IllegalArgumentException("A serve command only applies to http/sse servers");
            }
        } else if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("The " + transport + " MCP server '" + name + "' requires a url");
        }
    }

    public static Builder stdio(String name, String command) {
        return new Builder(name, McpTransport.STDIO).command(command);
    }

    public static Builder remote(String name, McpTransport transport, String url) {
        return new Builder(name, transport).url(url);
    }

    public static Builder builder(String name, McpTransport transport) {
        return new Builder(name, transport);
    }

    /** The command line for stdio, the url otherwise. */
    public String target() {
        if (transport == McpTransport.STDIO) {
            return args.isEmpty() ? command : command + " " + String.join(" ", args);
        }
        return url;
    }

    public static final class Builder {
        private final String name;
        private final McpTransport transport;
        private String command;
        private final List<String> args = new ArrayList<>();
        private final Map<String, String> env = new LinkedHashMap<>();
        private String url;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private McpServeSpec serve;

        private Builder(String name, McpTransport transport) {
            this.name = name;
            this.transport = transport;
        }

        public Builder command(String command) {
            this.command = command;
            return this;
        }

        public Builder args(List<String> args) {
            if (args != null) {
                this.args.addAll(args);
            }
            return this;
        }

        public Builder arg(String arg) {
            this.args.add(arg);
            return this;
        }

        public Builder env(Map<String, String> env) {
            if (env != null) {
                this.env.putAll(env);
            }
            return this;
        }

        public Builder env(String key, String value) {
            this.env.put(key, value);
            return this;
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            if (headers != null) {
                this.headers.putAll(headers);
            }
            return this;
        }

        public Builder header(String key, String value) {
            this.headers.put(key, value);
            return this;
        }

        public Builder serve(McpServeSpec serve) {
            this.serve = serve;
            return this;
        }

        public McpServer build() {
            return new McpServer(name, transport, command, args, env, url, headers, serve);
        }
    }
}
