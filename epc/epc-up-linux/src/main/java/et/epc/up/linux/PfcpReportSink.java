package et.epc.up.linux;

import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.pfcp.PfcpCodec;
import com.microjainslee.ra.pfcp.PfcpMessage;
import com.microjainslee.ra.pfcp.PfcpMessageType;
import com.microjainslee.ra.pfcp.PfcpUsageReport;
import com.microjainslee.ra.pfcp.command.SendPfcpMessage;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default {@link ReportSink}: PFCP Session Report Request carrying the encoded
 * usage reports, sent through the {@code pfcp-ra} command port
 * ({@link SendPfcpMessage} is the RA's raw-payload path). No payload dumps on
 * the hot path.
 */
public final class PfcpReportSink implements ReportSink {

    private final RaCommandPort pfcpRa;
    private final InetSocketAddress cpPeer;
    private final AtomicInteger sequence = new AtomicInteger(1);

    public PfcpReportSink(RaCommandPort pfcpRa, InetSocketAddress cpPeer) {
        this.pfcpRa = Objects.requireNonNull(pfcpRa, "pfcpRa");
        this.cpPeer = Objects.requireNonNull(cpPeer, "cpPeer");
    }

    @Override
    public void report(long seid, List<PfcpUsageReport> reports) {
        if (reports == null || reports.isEmpty()) {
            return;
        }
        int seq = sequence.getAndIncrement() & 0xff_ffff;
        PfcpMessage message = new PfcpMessage(PfcpMessageType.SESSION_REPORT_REQUEST,
                seid, seq, PfcpCodec.encodeUsageReports(reports));
        pfcpRa.sendCommand(new SendPfcpMessage(message, cpPeer));
    }
}
