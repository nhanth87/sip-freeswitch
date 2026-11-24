package et.epc.hss.sbbs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.jss7.command.Ss7Command;
import com.microjainslee.ra.jss7.event.Ss7MapEvent;
import et.epc.hss.HeapHssRepository;
import et.epc.hss.HeapSecretStore;
import et.epc.hss.HlrAuthService;
import et.epc.hss.HssRepository;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.restcomm.protocols.ss7.map.api.MAPMessageType;

/**
 * H-R2: a throwing repository on MAP SendAuthenticationInfo still emits exactly one
 * MAP error (systemFailure, TS 29.002 §8.5.2) on the request dialog.
 */
class HlrSbbCatchAllTest {

    final List<OutboundCommand> sent = new ArrayList<>();

    private HlrSbb sbbWith(HssRepository repo) throws Exception {
        HeapSecretStore secrets = new HeapSecretStore();
        secrets.put(HlrSbbTest.IMSI,
                et.epc.crypto.Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc"),
                et.epc.crypto.Bytes.parseHex("cd63cb71954a9f4e48a5994e37a02baf"),
                et.epc.crypto.Bytes.parseHex("8000"));
        HlrSbb sbb = new HlrSbb(new HlrAuthService(repo, secrets));
        Field f = HlrSbb.class.getDeclaredField("ss7Ra");
        f.setAccessible(true);
        f.set(sbb, (RaCommandPort) sent::add);
        return sbb;
    }

    @Test
    void throwingRepositoryAnswersSystemFailureOnTheRequestDialog() throws Exception {
        HeapHssRepository heap = new HeapHssRepository();
        HssRepository failing = (HssRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {HssRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("find")) {
                        throw new RuntimeException("db down");
                    }
                    return method.invoke(heap, args);
                });
        sbbWith(failing).onEvent(new Ss7MapEvent.Service("814",
                MAPMessageType.sendAuthenticationInfo_Request,
                HlrSbbTest.sai(HlrSbbTest.IMSI.digits(), 1, 3, 7)), null);
        assertEquals(1, sent.size(), "exactly one MAP error, no silent drop");
        Ss7Command.MapReturnError e = (Ss7Command.MapReturnError) sent.get(0);
        assertEquals("814", e.dialogId());
        assertEquals(7L, e.invokeId());
        assertEquals(HlrAuthService.ERR_SYSTEM_FAILURE, e.mapErrorCode());
    }
}
