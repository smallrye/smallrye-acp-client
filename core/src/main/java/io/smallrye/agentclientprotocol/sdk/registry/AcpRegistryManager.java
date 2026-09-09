package io.smallrye.agentclientprotocol.sdk.registry;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.agentclientprotocol.sdk.registry.model.Agent;
import io.smallrye.agentclientprotocol.sdk.registry.model.AgentCommand;
import io.smallrye.agentclientprotocol.sdk.registry.model.Distribution;
import io.smallrye.agentclientprotocol.sdk.registry.model.InstalledAgent;
import io.smallrye.agentclientprotocol.sdk.registry.model.NpxInfo;
import io.smallrye.agentclientprotocol.sdk.registry.model.PlatformBinary;
import io.smallrye.agentclientprotocol.sdk.registry.model.Registry;
import io.smallrye.agentclientprotocol.sdk.registry.model.UvxInfo;

/**
 * Manages the ACP agent registry: fetching, caching, installing and resolving agents.
 *
 * <p>
 * Agents are installed under {@code $HOME/.acp/agents/<agent-id>/}.
 * A cached copy of the remote registry is kept at {@code $HOME/.acp/registry.json}.
 */
public class AcpRegistryManager {

    private static final Logger logger = LoggerFactory.getLogger(AcpRegistryManager.class);

    public static final String REGISTRY_URL = "https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json";

    public static final Path ACP_HOME = Path.of(System.getProperty("user.home"), ".acp");
    public static final Path AGENTS_DIR = ACP_HOME.resolve("agents");
    public static final Path REGISTRY_CACHE = ACP_HOME.resolve("registry.json");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final OutputHandler output;

    public AcpRegistryManager() {
        this(OutputHandler.console());
    }

    public AcpRegistryManager(OutputHandler output) {
        this.output = output;
    }

    // ── Registry operations ─────────────────────────────────────────────────

    public Registry fetchRegistry() throws IOException, InterruptedException {
        logger.info("Fetching ACP registry from " + REGISTRY_URL);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(REGISTRY_URL))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch registry: HTTP " + response.statusCode());
        }

        String json = response.body();
        Files.createDirectories(ACP_HOME);
        Files.writeString(REGISTRY_CACHE, json);
        logger.info("Registry cached at {}", REGISTRY_CACHE);

        return MAPPER.readValue(json, Registry.class);
    }

    public Registry getCachedRegistry() {
        if (!Files.exists(REGISTRY_CACHE)) {
            return null;
        }
        try {
            return MAPPER.readValue(REGISTRY_CACHE.toFile(), Registry.class);
        } catch (IOException e) {
            logger.warn("Failed to read cached registry: {}", e.getMessage());
            return null;
        }
    }

    public Registry getOrFetchRegistry() throws IOException, InterruptedException {
        Registry cached = getCachedRegistry();
        return cached != null ? cached : fetchRegistry();
    }

    public Agent findAgent(Registry registry, String agentId) {
        if (registry == null || registry.agents() == null)
            return null;
        return registry.agents().stream()
                .filter(a -> a.id().equals(agentId))
                .findFirst()
                .orElse(null);
    }

    // ── Install operations ──────────────────────────────────────────────────

    public void installAgent(String agentId, boolean force) throws IOException, InterruptedException {
        Registry registry = fetchRegistry();
        Agent agent = findAgent(registry, agentId);

        if (agent == null) {
            output.error("Agent '" + agentId + "' not found in the ACP registry.");
            output.error("");
            output.error("Run:  acp reg list --registry   to see available agents.");
            return;
        }

        Path agentDir = AGENTS_DIR.resolve(agentId);

        if (Files.exists(agentDir) && !force) {
            InstalledAgent existing = getInstalledAgent(agentId);
            if (existing != null) {
                output.info("Agent '" + agentId + "' is already installed (version "
                        + existing.version() + ").");
                output.info("Use --force to reinstall.");
                return;
            }
        }

        if (Files.exists(agentDir) && force) {
            RegistryUtils.deleteDirectory(agentDir);
        }
        Files.createDirectories(agentDir);

        String platform = RegistryUtils.detectPlatform();
        Distribution dist = agent.distribution();

        if (dist == null) {
            output.error("Agent '" + agentId + "' has no distribution information.");
            return;
        }

        if (dist.hasBinary()) {
            PlatformBinary platformBinary = dist.binary().get(platform);
            if (platformBinary != null) {
                installBinaryAgent(agent, platformBinary, platform, agentDir);
                return;
            }
            logger.warn("No binary for platform '{}'. Checking npx/uvx fallback...", platform);
        }

        if (dist.hasNpx()) {
            installNpxAgent(agent, agentDir);
            return;
        }

        if (dist.hasUvx()) {
            installUvxAgent(agent, agentDir);
            return;
        }

        output.error("No suitable distribution found for agent '"
                + agentId + "' on platform '" + platform + "'.");
    }

    private void installBinaryAgent(Agent agent, PlatformBinary platformBinary,
            String platform, Path agentDir)
            throws IOException, InterruptedException {

        String archiveUrl = platformBinary.archive();
        output.info("Downloading " + agent.name() + " v" + agent.version()
                + " for " + platform + " ...");
        output.info("  URL: " + archiveUrl);

        Path archiveFile = RegistryUtils.downloadFile(archiveUrl, agentDir);
        output.info("  Downloaded: " + archiveFile.getFileName());

        output.info("  Extracting ...");
        RegistryUtils.extractArchive(archiveFile, agentDir);

        String cmd = platformBinary.cmd();
        if (cmd.startsWith("./")) {
            cmd = cmd.substring(2);
        }
        Path binaryPath = agentDir.resolve(cmd);

        RegistryUtils.makeExecutable(binaryPath);

        if (!Files.exists(binaryPath)) {
            logger.warn("Expected binary not found at {} — searching...", binaryPath);
            String cmdName = Path.of(cmd).getFileName().toString();
            try (Stream<Path> walk = Files.walk(agentDir)) {
                Optional<Path> found = walk
                        .filter(p -> p.getFileName().toString().equals(cmdName))
                        .filter(p -> !p.toString().endsWith(".json"))
                        .findFirst();
                if (found.isPresent()) {
                    binaryPath = found.get();
                    cmd = agentDir.relativize(binaryPath).toString();
                    output.info("  Found binary at: " + binaryPath);
                    RegistryUtils.makeExecutable(binaryPath);
                }
            }
        }

        List<String> args = platformBinary.args() != null ? platformBinary.args() : List.of();
        InstalledAgent installed = new InstalledAgent(
                agent.id(), agent.name(), agent.version(), platform,
                "binary",
                agentDir.resolve(cmd).toAbsolutePath().toString(),
                args, null, null,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        saveInstalledAgent(agent.id(), installed);

        output.info("  Installed '" + agent.id() + "' v" + agent.version());
        output.info("  Binary: " + installed.cmd());
    }

    private void installNpxAgent(Agent agent, Path agentDir)
            throws IOException, InterruptedException {

        NpxInfo npx = agent.distribution().npx();
        String packageName = npx.packageName();
        output.info("Installing npx agent: " + agent.name() + " v" + agent.version());
        output.info("  Package: " + packageName);

        output.info("  Running: npm install " + packageName + " ...");
        RegistryUtils.runProcess(agentDir, "npm", "install", "--prefix", agentDir.toString(), packageName);

        String binName = RegistryUtils.resolveBinName(packageName);
        Path binPath = agentDir.resolve("node_modules").resolve(".bin").resolve(binName);

        if (!Files.exists(binPath)) {
            Path binDir = agentDir.resolve("node_modules").resolve(".bin");
            if (Files.exists(binDir)) {
                try (Stream<Path> bins = Files.list(binDir)) {
                    Optional<Path> found = bins.filter(Files::isExecutable).findFirst();
                    if (found.isPresent()) {
                        binPath = found.get();
                        binName = binPath.getFileName().toString();
                        output.info("  Resolved binary: " + binName);
                    }
                }
            }
        }

        List<String> args = npx.args() != null ? npx.args() : List.of();
        InstalledAgent installed = new InstalledAgent(
                agent.id(), agent.name(), agent.version(), RegistryUtils.detectPlatform(),
                "npx",
                binPath.toAbsolutePath().toString(),
                args, packageName, null,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        saveInstalledAgent(agent.id(), installed);

        output.info("  Installed '" + agent.id() + "' v" + agent.version());
        output.info("  Binary: " + binPath.toAbsolutePath());
    }

    private void installUvxAgent(Agent agent, Path agentDir)
            throws IOException, InterruptedException {

        UvxInfo uvx = agent.distribution().uvx();
        String packageName = uvx.packageName();
        output.info("Installing uvx agent: " + agent.name() + " v" + agent.version());
        output.info("  Package: " + packageName);

        Path venvDir = agentDir.resolve("venv");
        output.info("  Running: uv pip install " + packageName + " ...");
        RegistryUtils.runProcess(agentDir, "uv", "venv", venvDir.toString());
        RegistryUtils.runProcess(agentDir, "uv", "pip", "install",
                "--python", venvDir.resolve("bin").resolve("python").toString(),
                packageName);

        String binName = RegistryUtils.resolveBinName(packageName);
        Path binPath = venvDir.resolve("bin").resolve(binName);

        if (!Files.exists(binPath)) {
            Path binDir = venvDir.resolve("bin");
            if (Files.exists(binDir)) {
                try (Stream<Path> bins = Files.list(binDir)) {
                    Optional<Path> found = bins
                            .filter(Files::isExecutable)
                            .filter(p -> {
                                String n = p.getFileName().toString();
                                return !n.startsWith("python") && !n.startsWith("pip")
                                        && !n.equals("activate");
                            })
                            .findFirst();
                    if (found.isPresent()) {
                        binPath = found.get();
                        binName = binPath.getFileName().toString();
                        output.info("  Resolved binary: " + binName);
                    }
                }
            }
        }

        List<String> args = uvx.args() != null ? uvx.args() : List.of();
        InstalledAgent installed = new InstalledAgent(
                agent.id(), agent.name(), agent.version(), RegistryUtils.detectPlatform(),
                "uvx",
                binPath.toAbsolutePath().toString(),
                args, null, packageName,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        saveInstalledAgent(agent.id(), installed);

        output.info("  Installed '" + agent.id() + "' v" + agent.version());
        output.info("  Binary: " + binPath.toAbsolutePath());
    }

    // ── Installed-agent operations ──────────────────────────────────────────

    public InstalledAgent getInstalledAgent(String agentId) {
        Path metadataFile = AGENTS_DIR.resolve(agentId).resolve("agent.json");
        if (!Files.exists(metadataFile)) {
            return null;
        }
        try {
            return MAPPER.readValue(metadataFile.toFile(), InstalledAgent.class);
        } catch (IOException e) {
            logger.warn("Failed to read metadata for '{}': {}", agentId, e.getMessage());
            return null;
        }
    }

    public List<InstalledAgent> listInstalled() {
        if (!Files.exists(AGENTS_DIR)) {
            return List.of();
        }
        List<InstalledAgent> result = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(AGENTS_DIR)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                InstalledAgent a = getInstalledAgent(dir.getFileName().toString());
                if (a != null)
                    result.add(a);
            });
        } catch (IOException e) {
            logger.warn("Failed to list installed agents: {}", e.getMessage());
        }
        return result;
    }

    public boolean isInstalled(String agentId) {
        return getInstalledAgent(agentId) != null;
    }

    public void removeAgent(String agentId) throws IOException {
        InstalledAgent installed = getInstalledAgent(agentId);
        if (installed == null) {
            output.error("Agent '" + agentId + "' is not installed.");
            output.error("");
            output.error("Run:  acp reg list   to see installed agents.");
            return;
        }

        Path agentDir = AGENTS_DIR.resolve(agentId);
        RegistryUtils.deleteDirectory(agentDir);
        output.info("Agent '" + agentId + "' (v" + installed.version() + ") removed.");
    }

    private void saveInstalledAgent(String agentId, InstalledAgent installed) throws IOException {
        Path metadataFile = AGENTS_DIR.resolve(agentId).resolve("agent.json");
        Files.createDirectories(metadataFile.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(metadataFile.toFile(), installed);
    }

    // ── Agent resolution ────────────────────────────────────────────────────

    public AgentCommand resolveAgentCommand(String agentId) {
        InstalledAgent installed = getInstalledAgent(agentId);
        if (installed == null) {
            return null;
        }

        List<String> args = installed.args() != null ? installed.args() : List.of();
        return new AgentCommand(installed.cmd(), args);
    }
}
