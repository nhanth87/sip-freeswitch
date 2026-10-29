package et.epc.hss.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.api.annotations.SbbAnnotation;
import com.microjainslee.ra.jss7.command.Ss7Command;
import com.microjainslee.ra.jss7.event.Ss7MapEvent;
import et.epc.api.Imsi;
import et.epc.hss.HlrAuthService;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.ReSynchronisationInfo;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.SendAuthenticationInfoRequest;

/**
 * HLR/AuC MAP face: answers SendAuthenticationInfo from VLR/SGSN (TS 29.002 §8.5.2) on the
 * dialog that carried the request. Vector generation lives in {@link HlrAuthService}
 * (Postgres SoT, TS 33.102 Annex C SQN); ra-jss7 only encodes MAP.
 */
@SbbAnnotation(name = "HlrSbb", vendor = "et.epc", version = "1.0")
public class HlrSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(HlrSbb.class);

    private final HlrAuthService auc;

    @InjectRa(name = "ra-jss7")
    private volatile RaCommandPort ss7Ra;

    public HlrSbb() {
        this(null);
    }

    public HlrSbb(HlrAuthService auc) {
        this.auc = auc;
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (event instanceof Ss7MapEvent.Service svc && svc.message() instanceof SendAuthenticationInfoRequest sai) {
            onSendAuthenticationInfo(svc.dialogId(), sai);
        }
    }

    void onSendAuthenticationInfo(String dialogId, SendAuthenticationInfoRequest req) {
        if (auc == null || req.getImsi() == null) {
            send(new Ss7Command.MapReturnError(dialogId, req.getInvokeId(), HlrAuthService.ERR_UNEXPECTED_DATA_VALUE));
            return;
        }
        ReSynchronisationInfo resync = req.getReSynchronisationInfo();
        HlrAuthService.Answer answer = auc.sendAuthenticationInfo(
                new Imsi(req.getImsi().getData()),
                req.getNumberOfRequestedVectors(),
                (int) req.getMapProtocolVersion(),
                resync == null ? null : resync.getRand(),
                resync == null ? null : resync.getAuts());
        if (answer.mapError() != null) {
            send(new Ss7Command.MapReturnError(dialogId, req.getInvokeId(), answer.mapError()));
            return;
        }
        List<Ss7Command.AuthQuintuplet> q = answer.quintuplets().stream()
                .map(v -> new Ss7Command.AuthQuintuplet(v.rand(), v.xres(), v.ck(), v.ik(), v.autn()))
                .toList();
        List<Ss7Command.AuthTriplet> t = answer.triplets().stream()
                .map(v -> new Ss7Command.AuthTriplet(v.rand(), v.sres(), v.kc()))
                .toList();
        send(new Ss7Command.MapSendAuthenticationInfoResponse(dialogId, req.getInvokeId(), q, t));
        LOG.debug("[HLR] SAI dialog={} vectors={}", dialogId, q.isEmpty() ? t.size() : q.size());
    }

    private void send(Ss7Command cmd) {
        RaCommandPort port = ss7Ra;
        if (port == null) {
            LOG.warn("[HLR] ra-jss7 not injected — dropped {}", cmd.getClass().getSimpleName());
            return;
        }
        port.sendCommand(cmd);
    }
}
