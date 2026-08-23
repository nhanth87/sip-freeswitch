package et.epc.up.linux;

/**
 * Process-execution seam for kernel CLIs ({@code nft}, {@code ip}, {@code tc}).
 * Tests stub it; production uses the {@link ProcessBuilder}-backed default.
 */
@FunctionalInterface
public interface ProcessRunner {

    /** Runs argv, returns stdout. Non-zero exit / timeout throws {@link IllegalStateException}. */
    String run(String... argv);

    /** Default {@link ProcessBuilder}-backed runner. */
    static ProcessRunner processBuilder() {
        return ProcessBuilderRunner.INSTANCE;
    }
}
