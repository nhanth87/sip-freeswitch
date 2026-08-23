package et.epc.up.linux;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Default {@link ProcessRunner}: {@link ProcessBuilder} with captured stdout and a hard timeout. */
final class ProcessBuilderRunner implements ProcessRunner {

    static final ProcessBuilderRunner INSTANCE = new ProcessBuilderRunner();

    private static final long TIMEOUT_SECONDS = 10;
    private static final int ERROR_SNIPPET_MAX = 200;

    private ProcessBuilderRunner() {}

    @Override
    public String run(String... argv) {
        if (argv == null || argv.length == 0) {
            throw new IllegalArgumentException("argv is required");
        }
        try {
            Process process = new ProcessBuilder(argv).start();
            String stdout = drain(process.getInputStream());
            String stderr = drain(process.getErrorStream());
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("timeout after " + TIMEOUT_SECONDS + "s: " + argv[0]);
            }
            int exit = process.exitValue();
            if (exit != 0) {
                throw new IllegalStateException(
                        argv[0] + " exit=" + exit + ": " + snippet(stderr));
            }
            return stdout;
        } catch (IOException e) {
            throw new IllegalStateException("cannot run " + argv[0], e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted: " + argv[0], e);
        }
    }

    private static String drain(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        String trimmed = s.trim();
        return trimmed.length() <= ERROR_SNIPPET_MAX
                ? trimmed
                : trimmed.substring(0, ERROR_SNIPPET_MAX);
    }
}
