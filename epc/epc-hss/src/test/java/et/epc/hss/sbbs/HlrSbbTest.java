package et.epc.hss.sbbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.jss7.command.Ss7Command;
import com.microjainslee.ra.jss7.event.Ss7MapEvent;
import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.PlmnId;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
import et.epc.crypto.Bytes;
import et.epc.hss.HeapHssRepository;
import et.epc.hss.HeapSecretStore;
import et.epc.hss.HlrAuthService;
import et.epc.hss.SubscriberRecord;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.restcomm.protocols.ss7.map.api.MAPMessageType;
import org.restcomm.protocols.ss7.map.api.primitives.IMSI;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.SendAuthenticationInfoRequest;

class HlrSbbTest {

    static final Imsi IMSI = new Imsi("001010000000001");

    final List<OutboundCommand> sent = new ArrayList<>();

    HlrSbb sbb() throws Exception {
        HeapHssRepository repo = new HeapHssRepository();
        HeapSecretStore secrets = new HeapSecretStore();
        secrets.put(IMSI, Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc"),
                Bytes.parseHex("cd63cb71954a9f4e48a5994e37a02baf"), Bytes.parseHex("8000"));
        repo.save(new SubscriberRecord(new SubscriberProfile(IMSI, "1234567890", true, new PlmnId("001", "01"),
                List.of(ApnConfig.internet()), "internet", QosProfile.pilotDefault(), 0), 0));
        HlrSbb sbb = new HlrSbb(new HlrAuthService(repo, secrets));
        Field f = HlrSbb.class.getDeclaredField("ss7Ra");
        f.setAccessible(true);
        f.set(sbb, (RaCommandPort) sent::add);
        return sbb;
    }

    static SendAuthenticationInfoRequest sai(String imsi, int vectors, long mapVersion, long invokeId) {
        IMSI i = (IMSI) Proxy.newProxyInstance(HlrSbbTest.class.getClassLoader(), new Class<?>[] {IMSI.class},
                (p, m, a) -> "getData".equals(m.getName()) ? imsi : null);
        return (SendAuthenticationInfoRequest) Proxy.newProxyInstance(HlrSbbTest.class.getClassLoader(),
                new Class<?>[] {SendAuthenticationInfoRequest.class}, (p, m, a) -> switch (m.getName()) {
                    case "getImsi" -> i;
                    case "getNumberOfRequestedVectors" -> vectors;
                    case "getMapProtocolVersion" -> mapVersion;
                    case "getInvokeId" -> invokeId;
                    case "getMessageType" -> MAPMessageType.sendAuthenticationInfo_Request;
                    default -> m.getReturnType() == boolean.class ? false : null;
                });
    }

    @Test
    void saiIsAnsweredWithQuintupletsOnTheRequestDialog() throws Exception {
        sbb().onEvent(new Ss7MapEvent.Service("812", MAPMessageType.sendAuthenticationInfo_Request,
                sai(IMSI.digits(), 3, 3, 1)), null);
        Ss7Command.MapSendAuthenticationInfoResponse r = (Ss7Command.MapSendAuthenticationInfoResponse) sent.get(0);
        assertEquals("812", r.dialogId());
        assertEquals(1L, r.invokeId());
        assertEquals(3, r.quintuplets().size());
        assertEquals(16, r.quintuplets().get(0).autn().length);
        assertTrue(r.triplets().isEmpty());
    }

    @Test
    void unknownImsiReturnsUnknownSubscriber() throws Exception {
        sbb().onEvent(new Ss7MapEvent.Service("813", MAPMessageType.sendAuthenticationInfo_Request,
                sai("001019999999999", 1, 3, 4)), null);
        Ss7Command.MapReturnError e = (Ss7Command.MapReturnError) sent.get(0);
        assertEquals(HlrAuthService.ERR_UNKNOWN_SUBSCRIBER, e.mapErrorCode());
        assertEquals(4L, e.invokeId());
    }
}
