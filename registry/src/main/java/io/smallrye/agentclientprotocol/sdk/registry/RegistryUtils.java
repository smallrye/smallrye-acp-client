package io.smallrye.agentclientprotocol.sdk.registry;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

public final class RegistryUtils {

    private RegistryUtils() {
    }

    public static String detectPlatform() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();

        String osKey;
        if (os.contains("mac") || os.contains("darwin")) {
            osKey = "darwin";
        } else if (os.contains("linux")) {
            osKey = "linux";
        } else if (os.contains("win")) {
            osKey = "windows";
        } else {
            throw new RuntimeException("Unsupported OS: " + os);
        }

        String archKey;
        if ("aarch64".equals(arch) || "arm64".equals(arch)) {
            archKey = "aarch64";
        } else if ("x86_64".equals(arch) || "amd64".equals(arch)) {
            archKey = "x86_64";
        } else {
            throw new RuntimeException("Unsupported architecture: " + arch);
        }

        return osKey + "-" + archKey;
    }

    public static String resolveBinName(String packageSpec) {
        String name = packageSpec;
        int atVersion = name.lastIndexOf('@');
        if (atVersion > 0) {
            name = name.substring(0, atVersion);
        }
        int eqVersion = name.indexOf("==");
        if (eqVersion > 0) {
            name = name.substring(0, eqVersion);
        }
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name;
    }

    public static Path downloadFile(String url, Path targetDir) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IOException("Download failed: HTTP " + response.statusCode() + " from " + url);
        }

        String fileName = url.substring(url.lastIndexOf('/') + 1);
        if (fileName.contains("?")) {
            fileName = fileName.substring(0, fileName.indexOf('?'));
        }
        Path archiveFile = targetDir.resolve(fileName);

        try (InputStream in = response.body()) {
            Files.copy(in, archiveFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return archiveFile;
    }

    public static void extractArchive(Path archiveFile, Path targetDir)
            throws IOException, InterruptedException {

        String name = archiveFile.getFileName().toString().toLowerCase();

        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            runProcess(targetDir, "tar", "xzf", archiveFile.toString(), "-C", targetDir.toString());
        } else if (name.endsWith(".tar.xz")) {
            runProcess(targetDir, "tar", "xJf", archiveFile.toString(), "-C", targetDir.toString());
        } else if (name.endsWith(".zip")) {
            extractZip(archiveFile, targetDir);
        } else {
            throw new IOException("Unsupported archive format: " + name);
        }
    }

    public static void extractZip(Path archiveFile, Path targetDir) throws IOException {
        try (FileSystem zipFs = FileSystems.newFileSystem(archiveFile, Map.of())) {
            for (Path root : zipFs.getRootDirectories()) {
                try (Stream<Path> stream = Files.walk(root)) {
                    stream.forEach(source -> {
                        try {
                            Path dest = targetDir.resolve(root.relativize(source).toString());
                            if (Files.isDirectory(source)) {
                                Files.createDirectories(dest);
                            } else {
                                Files.createDirectories(dest.getParent());
                                Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                }
            }
        }
    }

    public static void makeExecutable(Path path) throws IOException, InterruptedException {
        if (!System.getProperty("os.name").toLowerCase().contains("win") && Files.exists(path)) {
            runProcess(path.getParent(), "chmod", "+x", path.toString());
        }
    }

    public static void runProcess(Path workDir, String... command)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .inheritIO()
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Command failed (exit " + exitCode + "): "
                    + String.join(" ", command));
        }
    }

    public static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir))
            return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
