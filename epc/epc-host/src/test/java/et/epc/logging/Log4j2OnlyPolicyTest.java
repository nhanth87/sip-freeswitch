package et.epc.logging;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Log4j2-only policy gate for the whole epc reactor (owner mandate
 * 2026-08-26). Scans every sibling module's {@code src/main/java} from the
 * epc-host basedir — app sources must log through
 * {@code org.apache.logging.log4j} only; jboss-logmanager stays the silent
 * Quarkus structural jar and must never receive an event.
 */
class Log4j2OnlyPolicyTest {

    private static final List<String> MODULES = List.of(
            "epc-api", "epc-crypto", "epc-hss", "epc-sgwc", "epc-pgwc",
            "epc-pcef", "epc-pcrf", "epc-up-linux", "epc-host");

    private static final Pattern FORBIDDEN = Pattern.compile(
            "^\\s*import\\s+(java\\.util\\.logging|org\\.jboss\\.logging|org\\.slf4j|org\\.apache\\.log4j)\\."
                    + "|^\\s*import\\s+static\\s+(java\\.util\\.logging|org\\.jboss\\.logging|org\\.slf4j)\\."
                    + "|System\\.(out|err)\\.(print|println|format|printf)");

    @Test
    void applicationSourcesUseLog4j2Only() throws IOException {
        Path reactorRoot = Path.of("..").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(reactorRoot), "reactor root missing: " + reactorRoot);
        List<String> violations = new ArrayList<>();
        for (String module : MODULES) {
            Path src = reactorRoot.resolve(module).resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(src)) {
                violations.addAll(scan(src));
            }
        }
        assertTrue(violations.isEmpty(),
                "Log4j2-only policy violated:\n  " + String.join("\n  ", violations));
    }

    private static List<String> scan(Path root) throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                int lineNo = 0;
                for (String line : readLines(p)) {
                    lineNo++;
                    if (FORBIDDEN.matcher(line).find()) {
                        violations.add(p + ":" + lineNo + ": " + line.strip());
                    }
                }
            });
        }
        return violations;
    }

    private static List<String> readLines(Path p) {
        try {
            return Files.readAllLines(p);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + p, e);
        }
    }
}
