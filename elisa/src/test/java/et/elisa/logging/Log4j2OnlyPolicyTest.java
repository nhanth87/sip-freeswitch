package et.elisa.logging;

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
 * Log4j2-only policy gate (owner mandate 2026-08-26).
 *
 * <p>Application sources must log exclusively through
 * {@code org.apache.logging.log4j}. Any other logging facade or backend —
 * {@code java.util.logging}, {@code org.jboss.logging}, {@code org.slf4j},
 * raw {@code System.out}/{@code System.err} — is a build failure. Facades used
 * by third-party libraries are routed INTO Log4j2 via the bridge jars declared
 * in {@code pom.xml} (log4j-slf4j2-impl, log4j-jul, log4j-1.2-api) and the
 * maven-enforcer bannedDependencies rule. {@code jboss-logmanager} itself is
 * the one structural exception (QuarkusEntryPoint force-sets it) — it stays on
 * the classpath silent and receives no events.</p>
 */
class Log4j2OnlyPolicyTest {

    private static final Path SRC_ROOT = Path.of("src/main/java");

    private static final Pattern FORBIDDEN = Pattern.compile(
            "^\\s*import\\s+(java\\.util\\.logging|org\\.jboss\\.logging|org\\.slf4j|org\\.apache\\.log4j)\\."
                    + "|^\\s*import\\s+static\\s+(java\\.util\\.logging|org\\.jboss\\.logging|org\\.slf4j)\\."
                    + "|System\\.(out|err)\\.(print|println|format|printf)");

    @Test
    void applicationSourcesUseLog4j2Only() throws IOException {
        assertTrue(Files.isDirectory(SRC_ROOT), "source root missing: " + SRC_ROOT);
        List<String> violations = scan(SRC_ROOT);
        assertTrue(violations.isEmpty(),
                "Log4j2-only policy violated:\n  " + String.join("\n  ", violations));
    }

    static List<String> scan(Path root) throws IOException {
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
