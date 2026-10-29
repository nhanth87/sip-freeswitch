package et.epc.pcrf;

/** Test bridge to the package-private SQL constants. */
public final class JdbcPolicyRepositoryAccess {
    private JdbcPolicyRepositoryAccess() {
    }

    public static String upsertRuleSql() {
        return JdbcPolicyRepository.SQL_UPSERT_RULE;
    }

    public static String imsisOnApnSql() {
        return JdbcPolicyRepository.SQL_IMSIS_ON_APN;
    }
}
