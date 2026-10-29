package et.elisa.admin;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AdminPageRenderer {
    @ConfigProperty(name = "sf.html-dir") java.util.Optional<String> configuredRoot = java.util.Optional.empty();

    public String page(String name, Map<String, String> vars) { return render("admin/" + name, vars); }
    public String partial(String name, Map<String, String> vars) { return render("partials/" + name, vars); }
    public byte[] staticAsset(String name) {
        return read("static/" + name).orElseGet(() -> read("admin/static/" + name).orElse(null));
    }
    private String render(String relative, Map<String, String> vars) {
        String html = read(relative).map(bytes -> new String(bytes, StandardCharsets.UTF_8)).orElse("");
        for (var entry : vars.entrySet()) html = html.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        return html.replaceAll("\\{\\{[A-Z0-9_]+}}", "");
    }
    private java.util.Optional<byte[]> read(String relative) {
        if (relative.contains("..")) return java.util.Optional.empty();
        try {
            Path root = root(); Path file = root.resolve(relative).normalize();
            return file.startsWith(root) && Files.isRegularFile(file) ? java.util.Optional.of(Files.readAllBytes(file)) : java.util.Optional.empty();
        } catch (IOException ex) { return java.util.Optional.empty(); }
    }
    private Path root() {
        String configured = configuredRoot.orElse("");
        if (!configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize();
        String appHome = System.getenv("APP_HOME");
        if (appHome != null && Files.isDirectory(Path.of(appHome, "app", "html"))) return Path.of(appHome, "app", "html");
        Path local = Path.of("app/html"); return Files.isDirectory(local) ? local.toAbsolutePath() : Path.of("../app/html").toAbsolutePath();
    }
    public static String esc(Object value) {
        return value == null ? "" : value.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
