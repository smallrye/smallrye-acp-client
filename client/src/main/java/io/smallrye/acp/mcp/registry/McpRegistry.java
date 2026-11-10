package io.smallrye.acp.mcp.registry;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.smallrye.acp.mcp.model.McpScope;
import io.smallrye.acp.mcp.model.McpServer;

/**
 * The acp MCP server registry: {@code ~/.acp/mcp.json} ({@link McpScope#HOME}) and {@code ./.acp/mcp.json}
 * ({@link McpScope#LOCAL}). When an id is defined in both, the local definition wins.
 */
public class McpRegistry {

    public static final String MCP_SERVERS = "mcpServers";

    /**
     * A registry entry.
     *
     * @param scope where it is defined
     * @param file the registry file
     * @param name the server id
     * @param server the definition, {@code null} when it cannot be parsed
     * @param error parse error, {@code null} otherwise
     * @param shadowed {@code true} for a home entry overridden by a local entry with the same id
     */
    public record Entry(McpScope scope, Path file, String name, McpServer server, String error, boolean shadowed) {
    }

    private static final DefaultPrettyPrinter PRINTER = new DefaultPrettyPrinter()
            .withSeparators(Separators.createDefaultInstance()
                    .withObjectFieldValueSpacing(Separators.Spacing.AFTER))
            .withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);

    private final Path home;
    private final Path workingDir;

    public McpRegistry() {
        this(Path.of(System.getProperty("user.home")), Path.of(System.getProperty("user.dir")));
    }

    public McpRegistry(Path home, Path workingDir) {
        this.home = home;
        this.workingDir = workingDir;
    }

    public Path file(McpScope scope) {
        Path base = scope == McpScope.HOME ? home : workingDir;
        return base.resolve(".acp").resolve("mcp.json");
    }

    /** Lists the entries of a scope, or of both scopes (home first) when {@code scope} is {@code null}. */
    public List<Entry> list(McpScope scope) throws IOException {
        List<Entry> local = read(McpScope.LOCAL);
        if (scope == McpScope.LOCAL) {
            return local;
        }
        Set<String> localNames = new HashSet<>();
        local.forEach(e -> localNames.add(e.name()));
        List<Entry> result = new ArrayList<>();
        for (Entry e : read(McpScope.HOME)) {
            result.add(new Entry(e.scope(), e.file(), e.name(), e.server(), e.error(),
                    scope == null && localNames.contains(e.name())));
        }
        if (scope == null) {
            result.addAll(local);
        }
        return result;
    }

    /** Finds the effective definition of {@code id}: local first, then home. */
    public Optional<Entry> find(String id) throws IOException {
        for (McpScope scope : List.of(McpScope.LOCAL, McpScope.HOME)) {
            Optional<Entry> entry = find(scope, id);
            if (entry.isPresent()) {
                return entry;
            }
        }
        return Optional.empty();
    }

    public Optional<Entry> find(McpScope scope, String id) throws IOException {
        return read(scope).stream().filter(e -> e.name().equals(id)).findFirst();
    }

    /** Returns the effective, parsed definition of {@code id}. */
    public McpServer require(String id) throws IOException {
        Optional<Entry> found = find(id);
        if (found.isEmpty()) {
            throw new IllegalArgumentException(unknownMessage(id));
        }
        Entry entry = found.get();
        if (entry.server() == null) {
            throw new IllegalArgumentException("Invalid MCP server '" + id + "' in " + entry.file() + ": "
                    + entry.error());
        }
        return entry.server();
    }

    public String unknownMessage(String id) throws IOException {
        List<String> names = list(null).stream().filter(e -> !e.shadowed()).map(Entry::name).toList();
        return "Unknown MCP server '" + id + "'. "
                + (names.isEmpty() ? "No MCP server registered: use 'acp mcp add' to add one."
                        : "Registered: " + String.join(", ", names));
    }

    /** Adds or replaces a server, keeping the rest of the file untouched. */
    public void put(McpScope scope, McpServer server) throws IOException {
        Path file = file(scope);
        ObjectNode root = load(file);
        JsonNode servers = root.get(MCP_SERVERS);
        ObjectNode map = servers instanceof ObjectNode o ? o : root.putObject(MCP_SERVERS);
        map.set(server.name(), McpServerJson.toJson(server));
        save(file, root);
    }

    public boolean remove(McpScope scope, String id) throws IOException {
        Path file = file(scope);
        if (!Files.exists(file)) {
            return false;
        }
        ObjectNode root = load(file);
        JsonNode servers = root.get(MCP_SERVERS);
        if (!(servers instanceof ObjectNode map) || !map.has(id)) {
            return false;
        }
        map.remove(id);
        save(file, root);
        return true;
    }

    private List<Entry> read(McpScope scope) throws IOException {
        Path file = file(scope);
        List<Entry> entries = new ArrayList<>();
        JsonNode servers = load(file).path(MCP_SERVERS);
        servers.fields().forEachRemaining(e -> {
            try {
                entries.add(new Entry(scope, file, e.getKey(), McpServerJson.fromJson(e.getKey(), e.getValue()),
                        null, false));
            } catch (RuntimeException ex) {
                entries.add(new Entry(scope, file, e.getKey(), null, ex.getMessage(), false));
            }
        });
        return entries;
    }

    private static ObjectNode load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return McpServerJson.MAPPER.createObjectNode();
        }
        String content = Files.readString(file);
        if (content.isBlank()) {
            return McpServerJson.MAPPER.createObjectNode();
        }
        JsonNode node = McpServerJson.MAPPER.readTree(content);
        if (!(node instanceof ObjectNode object)) {
            throw new IOException(file + " does not contain a JSON object");
        }
        return object;
    }

    private static void save(Path file, ObjectNode root) throws IOException {
        String json = McpServerJson.MAPPER.writer(PRINTER).writeValueAsString(root) + System.lineSeparator();
        Path absolute = file.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path tmp = Files.createTempFile(absolute.getParent(), ".mcp", ".json.tmp");
        try {
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
