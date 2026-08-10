package et.elisa.ims.charging;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * CGRates / offline charging knobs (grill G1–G5). Peer live = HTTP evidence, never config-applied.
 */
@ApplicationScoped
public class CgratesConfig {

    @ConfigProperty(name = "sf.cgrates.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "sf.cgrates.url", defaultValue = "http://127.0.0.1:2080")
    String url;

    @ConfigProperty(name = "sf.cgrates.tenant", defaultValue = "cgrates.org")
    String tenant;

    @ConfigProperty(name = "sf.cgrates.cdrs.enabled", defaultValue = "false")
    boolean cdrsEnabled;

    /** Account key preference: {@code msisdn} (default) or {@code impi}. */
    @ConfigProperty(name = "sf.cgrates.account-key", defaultValue = "msisdn")
    String accountKey;

    @ConfigProperty(name = "sf.cgrates.timeout-ms", defaultValue = "2000")
    int timeoutMs;

    /** Master gate for offline dual-write (CDRsV1 + Rf ACR stub). */
    @ConfigProperty(name = "sf.charging.dual-write", defaultValue = "false")
    boolean dualWrite;

    @ConfigProperty(name = "sf.rf.enabled", defaultValue = "false")
    boolean rfEnabled;

    /** {@code deferred} (default) or {@code live} — SessionS gate is Phase C. */
    @ConfigProperty(name = "sf.ocs.mode", defaultValue = "deferred")
    String ocsMode;

    @ConfigProperty(name = "sf.ocs.session.enabled", defaultValue = "false")
    boolean ocsSessionEnabled;

    @ConfigProperty(name = "sf.ocs.pending.sweep-interval-sec", defaultValue = "30")
    int pendingSweepIntervalSec;

    public boolean enabled() {
        return enabled;
    }

    public String url() {
        return url == null || url.isBlank() ? "http://127.0.0.1:2080" : url.trim();
    }

    public String tenant() {
        return tenant == null || tenant.isBlank() ? "cgrates.org" : tenant.trim();
    }

    public boolean cdrsEnabled() {
        return cdrsEnabled;
    }

    public String accountKey() {
        return accountKey == null || accountKey.isBlank() ? "msisdn" : accountKey.trim();
    }

    public int timeoutMs() {
        return timeoutMs <= 0 ? 2000 : timeoutMs;
    }

    public boolean dualWrite() {
        return dualWrite;
    }

    public boolean rfEnabled() {
        return rfEnabled;
    }

    public String ocsMode() {
        return ocsMode == null || ocsMode.isBlank() ? "deferred" : ocsMode.trim();
    }

    public boolean ocsDeferred() {
        return !"live".equalsIgnoreCase(ocsMode());
    }

    public boolean ocsSessionEnabled() {
        return ocsSessionEnabled;
    }

    public int pendingSweepIntervalSec() {
        return pendingSweepIntervalSec <= 0 ? 30 : pendingSweepIntervalSec;
    }

    /** Offline CDRsV1 path: dual-write gate + client + cdrs flag. */
    public boolean cdrsDualWriteActive() {
        return dualWrite && enabled && cdrsEnabled;
    }

    /** Offline Rf stub path: dual-write gate + rf flag. */
    public boolean rfDualWriteActive() {
        return dualWrite && rfEnabled;
    }

    /** Unit-test helper (non-CDI). */
    static CgratesConfig forTest(boolean dualWrite, boolean enabled, boolean cdrsEnabled,
                                 boolean rfEnabled, String url) {
        CgratesConfig c = new CgratesConfig();
        c.dualWrite = dualWrite;
        c.enabled = enabled;
        c.cdrsEnabled = cdrsEnabled;
        c.rfEnabled = rfEnabled;
        c.url = url == null ? "http://127.0.0.1:2080" : url;
        c.tenant = "cgrates.org";
        c.accountKey = "msisdn";
        c.timeoutMs = 2000;
        c.ocsMode = "deferred";
        c.ocsSessionEnabled = false;
        c.pendingSweepIntervalSec = 30;
        return c;
    }
}
