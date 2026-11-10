package io.smallrye.acp.mcp.registry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.smallrye.acp.mcp.model.McpServeSpec;
import io.smallrye.acp.mcp.model.McpServer;

/**
 * Expands {@code ${VAR}} and {@code ${VAR:-default}} references.
 *
 * <p>
 * Agents only expand variables in their own configuration files; values sent in an ACP {@code session/new} request
 * are used literally. acp therefore expands them before sending a registered server to the agent.
 */
public final class McpEnv {

    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");

    private McpEnv() {
    }

    public static McpServer expand(McpServer server) {
        return expand(server, System::getenv);
    }

    /** Returns a copy of the server with every variable reference resolved. */
    public static McpServer expand(McpServer server, Function<String, String> env) {
        McpServeSpec serve = server.serve() == null ? null
                : new McpServeSpec(expand(server.serve().command(), env), expand(server.serve().args(), env),
                        expand(server.serve().env(), env));
        return McpServer.builder(server.name(), server.transport())
                .command(server.command() == null ? null : expand(server.command(), env))
                .args(expand(server.args(), env))
                .env(expand(server.env(), env))
                .url(server.url() == null ? null : expand(server.url(), env))
                .headers(expand(server.headers(), env))
                .serve(serve)
                .build();
    }

    /**
     * Expands the references of a value.
     *
     * @throws IllegalArgumentException when a variable without default is not set
     */
    public static String expand(String value, Function<String, String> env) {
        Matcher m = VAR.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String resolved = env.apply(m.group(1));
            if (resolved == null) {
                if (m.group(2) == null) {
                    throw new IllegalArgumentException("Environment variable " + m.group(1) + " is not set");
                }
                resolved = m.group(2);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static List<String> expand(List<String> values, Function<String, String> env) {
        return values.stream().map(v -> expand(v, env)).toList();
    }

    private static Map<String, String> expand(Map<String, String> values, Function<String, String> env) {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((k, v) -> result.put(k, expand(v, env)));
        return result;
    }
}
