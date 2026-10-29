package et.elisa.admin;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Path-jailed FreeSWITCH conf editor (grill G13c: freeswitch/conf only).
 */
@ApplicationScoped
public class FsConfService {

    private static final Logger LOG = LogManager.getLogger(FsConfService.class);
    private static final long MAX_FILE_BYTES = 512 * 1024;

    private final Path confRoot;
    private final String hardRestartCmd;

    public FsConfService(
            @ConfigProperty(name = "sf.fs.conf-dir") Optional<String> confDir,
            @ConfigProperty(name = "sf.fs.hard-restart-cmd", defaultValue = "") String hardRestartCmd) {
        this.confRoot = resolveConfRoot(confDir.orElse(null));
        this.hardRestartCmd = hardRestartCmd == null ? "" : hardRestartCmd.trim();
    }

    public Path confRoot() {
        return confRoot;
    }

    public boolean confRootExists() {
        return Files.isDirectory(confRoot);
    }

    public List<Map<String, String>> list(String relative) throws IOException {
        Path dir = resolveUnderConf(relative, true);
        if (!Files.isDirectory(dir)) {
            throw new IOException("Not a directory: " + relative);
        }
        List<Map<String, String>> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(p -> {
                        Map<String, String> row = new LinkedHashMap<>();
                        row.put("name", p.getFileName().toString());
                        row.put("path", relativize(p));
                        row.put("type", Files.isDirectory(p) ? "dir" : "file");
                        out.add(row);
                    });
        }
        return out;
    }

    public String readFile(String relative) throws IOException {
        Path file = resolveUnderConf(relative, false);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Not a file: " + relative);
        }
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            throw new IOException("File too large (max " + MAX_FILE_BYTES + " bytes)");
        }
        if (!isTextish(file)) {
            throw new IOException("Binary or unsupported file type");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public void writeFile(String relative, String content) throws IOException {
        Path file = resolveUnderConf(relative, false);
        if (content == null) {
            content = "";
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Content too large (max " + MAX_FILE_BYTES + " bytes)");
        }
        Path parent = file.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            if (!parent.startsWith(confRoot) && !parent.equals(confRoot)) {
                throw new IOException("Path escapes conf jail");
            }
            Files.createDirectories(parent);
        }
        if (Files.exists(file) && !Files.isRegularFile(file)) {
            throw new IOException("Target is not a file");
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
        LOG.info("fs_conf_write path=%s bytes=%d at=%s", relativize(file), bytes.length, Instant.now());
    }

    public void mkdir(String relative) throws IOException {
        Path dir = resolveUnderConf(relative, false);
        if (Files.exists(dir)) {
            throw new IOException("Already exists: " + relative);
        }
        Files.createDirectories(dir);
        LOG.info("fs_conf_mkdir path=%s", relativize(dir));
    }

    public Map<String, Object> hardRestart() throws IOException, InterruptedException {
        if (hardRestartCmd.isBlank()) {
            return Map.of(
                    "ok", false,
                    "message", "sf.fs.hard-restart-cmd is not configured");
        }
        ProcessBuilder pb = new ProcessBuilder("bash", "-lc", hardRestartCmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        boolean finished = p.waitFor(120, TimeUnit.SECONDS);
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            p.destroyForcibly();
            return Map.of("ok", false, "message", "Hard restart timed out", "output", truncate(out, 2000));
        }
        int code = p.exitValue();
        LOG.warn("fs_hard_restart exit=%d cmd=%s", code, hardRestartCmd);
        return Map.of(
                "ok", code == 0,
                "message", code == 0 ? "Hard restart completed" : "Hard restart failed exit=" + code,
                "output", truncate(out, 2000),
                "exitCode", code);
    }

    Path resolveUnderConf(String relative, boolean allowMissingLeaf) throws IOException {
        String rel = relative == null || relative.isBlank() ? "." : relative.trim();
        if (rel.startsWith("/") || rel.contains("..") || rel.contains("\\")) {
            throw new IOException("Invalid path");
        }
        Path resolved = confRoot.resolve(rel).normalize();
        if (!resolved.startsWith(confRoot)) {
            throw new IOException("Path escapes conf jail");
        }
        if (!allowMissingLeaf && !Files.exists(resolved) && Files.exists(resolved.getParent())) {
            // creating new file under existing parent is OK for write/mkdir callers that check
        }
        if (!resolved.equals(confRoot) && !resolved.startsWith(confRoot)) {
            throw new IOException("Path escapes conf jail");
        }
        return resolved;
    }

    private String relativize(Path p) {
        return confRoot.relativize(p).toString().replace('\\', '/');
    }

    private static boolean isTextish(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".xml")
                || name.endsWith(".conf")
                || name.endsWith(".cfg")
                || name.endsWith(".txt")
                || name.endsWith(".md")
                || name.endsWith(".json")
                || name.endsWith(".csv")
                || name.endsWith(".tpl")
                || name.endsWith(".noload")
                || !name.contains(".");
    }

    private static Path resolveConfRoot(String confDir) {
        if (confDir != null && !confDir.isBlank()) {
            return Path.of(confDir).toAbsolutePath().normalize();
        }
        String appHome = System.getenv("APP_HOME");
        List<Path> candidates = new ArrayList<>();
        if (appHome != null) {
            candidates.add(Path.of(appHome, "freeswitch", "conf"));
            candidates.add(Path.of(appHome, "..", "freeswitch", "conf"));
        }
        candidates.add(Path.of("freeswitch/conf"));
        candidates.add(Path.of("../freeswitch/conf"));
        for (Path c : candidates) {
            Path n = c.toAbsolutePath().normalize();
            if (Files.isDirectory(n)) {
                return n;
            }
        }
        return Path.of("freeswitch/conf").toAbsolutePath().normalize();
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    public Optional<String> parentPath(String relative) {
        if (relative == null || relative.isBlank() || ".".equals(relative)) {
            return Optional.empty();
        }
        Path p = Path.of(relative).normalize();
        Path parent = p.getParent();
        if (parent == null) {
            return Optional.of("");
        }
        return Optional.of(parent.toString().replace('\\', '/'));
    }
}
