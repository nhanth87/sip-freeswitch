package et.epc.up.linux;

import com.microjainslee.ra.pfcp.PfcpUsageReport;
import java.util.List;

/** Delivers PFCP usage reports toward the control-plane peer that owns a session SEID. */
@FunctionalInterface
public interface ReportSink {

    /** Report one polling pass worth of usage for all URRs under a session. */
    void report(long seid, List<PfcpUsageReport> reports);
}
