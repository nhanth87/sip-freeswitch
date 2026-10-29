package et.elisa.ims.cx;

import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.diameter.command.SendDiameterAnswer;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.ims.hss.HssLiteService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/** Cx server side when sf.ims.role.hss=true — answers UAR/MAR/SAR/LIR. */
@ApplicationScoped
public class CxServer {

    private static final Logger LOG = LogManager.getLogger(CxServer.class);

    @Inject ImsRoleFlags roles;
    @Inject HssLiteService hss;

    private volatile RaCommandPort diameterRa;

    public void bindDiameter(RaCommandPort port) {
        this.diameterRa = port;
    }

    public void onRequest(DiameterRequestEvent req) {
        if (!roles.hss() || req.applicationId() != CxCodec.CX_APP_ID) {
            return;
        }
        Map<Integer, String> avps = req.avps() == null ? Map.of() : req.avps();
        String impi = avps.getOrDefault(CxCodec.USER_NAME, "");
        String impu = avps.getOrDefault(CxCodec.PUBLIC_IDENTITY, "");
        Map<Integer, String> ans = switch (req.commandCode()) {
            case CxCodec.UAR -> hss.handleUar(impi, impu);
            case CxCodec.MAR -> hss.handleMar(impi, impu, req.sessionId(),
                    avps.get(CxCodec.SIP_AUTHORIZATION));
            case CxCodec.SAR -> hss.handleSar(impi, impu);
            case CxCodec.LIR -> hss.handleLir(impu);
            default -> Map.of(CxCodec.RESULT_CODE, "3001");
        };
        RaCommandPort port = diameterRa;
        if (port == null) {
            LOG.warn("CxServer: diameter-ra unbound");
            return;
        }
        port.sendCommand(new SendDiameterAnswer(
                req.sessionId(), CxCodec.CX_APP_ID, req.commandCode(),
                parseResult(ans), req.hopByHopId(), req.endToEndId(), ans));
    }

    private static long parseResult(Map<Integer, String> ans) {
        try {
            return Long.parseLong(ans.getOrDefault(CxCodec.RESULT_CODE, "2001"));
        } catch (NumberFormatException e) {
            return 2001;
        }
    }
}
