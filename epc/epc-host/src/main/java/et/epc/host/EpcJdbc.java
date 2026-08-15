package et.epc.host;

/**
 * Product JDBC is PostgreSQL on the dedicated {@code epc} database
 * ({@code postgres-epc}). Never H2/Derby/SQLite, never the Elisa {@code elisa} DB.
 */
public final class EpcJdbc {

    public static final String DEFAULT_URL = "jdbc:postgresql://postgres-epc:5432/epc";

    private EpcJdbc() {}

    public static void requirePostgres(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException("EPC JDBC URL is required (PostgreSQL postgres-epc / epc)");
        }
        String url = jdbcUrl.trim();
        if (url.startsWith("jdbc:h2:") || url.startsWith("jdbc:derby:") || url.startsWith("jdbc:sqlite:")) {
            throw new IllegalStateException("EPC JDBC must be PostgreSQL, not " + scheme(url));
        }
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException("EPC JDBC must be PostgreSQL, got: " + scheme(url));
        }
        if (url.contains("/elisa")) {
            throw new IllegalStateException("EPC JDBC must use the epc database, not Elisa elisa");
        }
    }

    private static String scheme(String url) {
        int colon = url.indexOf(':', 5);
        return colon < 0 ? url : url.substring(0, colon);
    }
}
