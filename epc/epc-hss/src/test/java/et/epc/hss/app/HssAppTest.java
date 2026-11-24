package et.epc.hss.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.diameter.command.SendDiameterAnswer;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import com.microjainslee.ra.httpserver.command.HttpServerCommand.HttpResponseExCommand;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;
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
import et.epc.hss.S6aAvp;
import et.epc.hss.SubscriberRecord;
import et.epc.hss.sbbs.HlrSbb;
import et.epc.hss.sbbs.HssSbb;
import et.epc.hss.sbbs.UdmUeauSbb;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.restcomm.protocols.ss7.map.api.MAPMessageType;
import org.restcomm.protocols.ss7.map.api.primitives.IMSI;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.SendAuthenticationInfoRequest;

/**
 * HSS app wiring: the three SBB faces, their event mappings, and the auto-deploy trap — an
 * APT-indexed SBB with a no-arg constructor would be auto-deployed under its annotation name and
 * shadow the {@code registerSbbType} factory (with no services) for {@code mapEventToSbb}.
 */
class HssAppTest {

    static final Imsi IMSI = new Imsi("001010000000001");
    static final PlmnId HOME = new PlmnId("001", "01");
    static final String UEAU_PATH = "/nudm-ueau/v1/imsi-" + IMSI.digits()
            + "/security-information/generate-auth-data";
    static final String UEAU_BODY = "{\"servingNetworkName\":\"5G:mnc001.mcc001.3gppnetwork.org\","
            + "\"ausfInstanceId\":\"8e1f2f1c-3a5d-4f4e-9d1a-0b2c3d4e5f60\"}";

    private MicroSleeContainer container;

    @AfterEach
    void tearDown() {
        if (container != null && container.getState() == MicroSleeContainer.State.STARTED) {
            container.stop();
        }
    }

    static MicroSleeContainer newContainer() {
        return new MicroSleeContainer(MicroSleeConfiguration.builder()
                .eventRouterBufferSize(64).preferVirtualThreads(false)
                .sbbPoolMin(2).sbbPoolMax(16).sbbPerVirtualThread(false).build());
    }

    /** Heap services with TS 35.208 test set 1 provisioned for {@link #IMSI}. */
    static HssBootstrap.Services seededServices() {
        HssBootstrap.Services svc = HssBootstrap.heap(HOME);
        ((HeapSecretStore) svc.secrets()).put(IMSI, Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc"),
                Bytes.parseHex("cd63cb71954a9f4e48a5994e37a02baf"), Bytes.parseHex("8000"));
        ((HeapHssRepository) svc.repo()).save(new SubscriberRecord(new SubscriberProfile(IMSI, "1234567890",
                true, HOME, List.of(ApnConfig.internet()), "internet", QosProfile.pilotDefault(), 0), 0));
        return svc;
    }

    @Test
    void installRegistersThreeSbbTypesAndTheirEventMappings() {
        container = newContainer();
        HssApp app = new HssApp();
        app.install(container, HssBootstrap.heap(HOME), null);

        assertTrue(container.getSbbTypeRegistry().isRegistered(HssSbb.class));
        assertTrue(container.getSbbTypeRegistry().isRegistered(HlrSbb.class));
        assertTrue(container.getSbbTypeRegistry().isRegistered(UdmUeauSbb.class));
        assertEquals(HssSbb.class, container.getSbbTypeRegistry().findTypeByName("HssSbb"));
        assertEquals(HlrSbb.class, container.getSbbTypeRegistry().findTypeByName("HlrSbb"));
        assertEquals(UdmUeauSbb.class, container.getSbbTypeRegistry().findTypeByName("UdmUeauSbb"));
        assertEquals(List.of(
                "SbbType:HssSbb",
                "EventMapping:DiameterRequestEvent->HssSbb",
                "SbbType:HlrSbb",
                "EventMapping:Ss7MapEvent.Service->HlrSbb",
                "EventMapping:Ss7MapEvent.Dialog->HlrSbb",
                "SbbType:UdmUeauSbb",
                "EventMapping:HttpWebRequestEvent->UdmUeauSbb"), app.wired());
    }

    @Test
    void installsOnlyTheRequestedFaces() {
        container = newContainer();
        HssApp app = new HssApp();
        app.install(container, HssBootstrap.heap(HOME), null, EnumSet.of(HssApp.Face.SBI));

        assertTrue(container.getSbbTypeRegistry().isRegistered(UdmUeauSbb.class));
        assertFalse(container.getSbbTypeRegistry().isRegistered(HssSbb.class));
        assertFalse(container.getSbbTypeRegistry().isRegistered(HlrSbb.class));
        assertEquals(List.of("SbbType:UdmUeauSbb", "EventMapping:HttpWebRequestEvent->UdmUeauSbb"), app.wired());
    }

    /**
     * Why HssMain installs only the enabled faces: micro-jainslee validates every registered SBB
     * type's {@code @InjectRa} against the registered RAs at start (strict mode).
     */
    @Test
    void aFaceWithoutItsRaAbortsStart() {
        container = newContainer();
        new HssApp().install(container, HssBootstrap.heap(HOME), null);
        FakeRa diameter = new FakeRa("diameter-ra");
        container.registerRa(diameter, diameter);

        IllegalStateException e = assertThrows(IllegalStateException.class, container::start);
        assertTrue(e.getMessage().contains("ra-jss7"), e.getMessage());
        assertTrue(e.getMessage().contains("http-server-ra"), e.getMessage());
    }

    @Test
    void noHssSbbCanBeInstantiatedWithoutItsServices() {
        for (Class<?> c : List.of(HssSbb.class, HssSbb.$Concrete.class, HlrSbb.class, UdmUeauSbb.class)) {
            assertTrue(Arrays.stream(c.getDeclaredConstructors()).noneMatch(k -> k.getParameterCount() == 0),
                    c.getName() + " must not have a no-arg constructor (APT auto-deploy would shadow the factory)");
        }
    }

    /**
     * The trap, reproduced: an APT index on the classpath lists all three HSS SBBs plus a probe SBB
     * that does have a no-arg constructor. The probe is auto-deployed (so the index really was
     * read); the HSS SBBs are not, and each event is answered by the factory instance with real
     * services — a service-less instance would answer 3002 / MAP error / 500.
     */
    @Test
    void factoryInstanceWithRealServicesHandlesEachEventEvenWithAnAptIndex(@TempDir Path dir) throws Exception {
        Path index = dir.resolve("META-INF/microjainslee/sbb-index.properties");
        Files.createDirectories(index.getParent());
        Files.writeString(index, String.join("\n",
                entry(0, HssSbb.class.getName(), "HssSbb"),
                entry(1, HlrSbb.class.getName(), "HlrSbb"),
                entry(2, UdmUeauSbb.class.getName(), "UdmUeauSbb"),
                entry(3, ShadowProbeSbb.class.getName(), "ShadowProbeSbb")) + "\n");

        Thread t = Thread.currentThread();
        ClassLoader original = t.getContextClassLoader();
        try (URLClassLoader withIndex = new URLClassLoader(new URL[] {dir.toUri().toURL()}, original)) {
            t.setContextClassLoader(withIndex); // the container captures its deployment loader here
            try {
                container = newContainer();
            } finally {
                t.setContextClassLoader(original);
            }
            HssApp app = new HssApp();
            app.install(container, seededServices(), null);
            FakeRa diameter = new FakeRa("diameter-ra");
            FakeRa ss7 = new FakeRa(HlrSbb.SS7_RA);
            FakeRa http = new FakeRa(UdmUeauSbb.HTTP_SERVER_RA);
            for (FakeRa ra : List.of(diameter, ss7, http)) {
                container.registerRa(ra, ra);
            }
            container.start();

            assertNotNull(container.getSbbLocalObject("ShadowProbeSbb"), "index was read and auto-deployed");
            assertNull(container.getSbbLocalObject("HssSbb"), "HssSbb must not be auto-deployed");
            assertNull(container.getSbbLocalObject("HlrSbb"), "HlrSbb must not be auto-deployed");
            assertNull(container.getSbbLocalObject("UdmUeauSbb"), "UdmUeauSbb must not be auto-deployed");

            // Diameter S6a AIR -> HssSbb with a real HssService: 2001 + E-UTRAN vector
            diameter.fire("diam-1", new DiameterRequestEvent("sess-1", HssSbb.S6A, 318, 9L, 10L,
                    "mme.host", "epc.mno", "hss.epc", "epc.lab", Map.of(
                            S6aAvp.USER_NAME, IMSI.digits(),
                            S6aAvp.VISITED_PLMN_ID, Bytes.toHex(HOME.snId()))));
            SendDiameterAnswer aia = assertInstanceOf(SendDiameterAnswer.class, diameter.next());
            assertEquals(2001L, aia.resultCode());
            assertTrue(aia.avps().containsKey(S6aAvp.KASME));

            // MAP SAI v3 -> HlrSbb with a real HlrAuthService: quintuplets on the request dialog
            ss7.fire("812", new Ss7MapEvent.Service("812", MAPMessageType.sendAuthenticationInfo_Request,
                    sai(IMSI.digits(), 2, 3, 1)));
            Ss7Command.MapSendAuthenticationInfoResponse sai =
                    assertInstanceOf(Ss7Command.MapSendAuthenticationInfoResponse.class, ss7.next());
            assertEquals("812", sai.dialogId());
            assertEquals(2, sai.quintuplets().size());

            // Nudm-UEAU -> UdmUeauSbb with a real UeauService: 200 5G_HE_AKA
            http.fire("h-1", new HttpWebRequestEvent("h-1", "POST", UEAU_PATH,
                    Map.of("Content-Type", "application/json"), UEAU_BODY));
            HttpResponseExCommand rsp = assertInstanceOf(HttpResponseExCommand.class, http.next());
            assertEquals(200, rsp.statusCode());
            assertTrue(rsp.textBody().contains("5G_HE_AKA"));
        }
    }

    private static String entry(int i, String cls, String name) {
        return "sbb." + i + ".class=" + cls + "\nsbb." + i + ".name=" + name
                + "\nsbb." + i + ".vendor=et.epc\nsbb." + i + ".version=1.0";
    }

    static SendAuthenticationInfoRequest sai(String imsi, int vectors, long mapVersion, long invokeId) {
        IMSI i = (IMSI) Proxy.newProxyInstance(HssAppTest.class.getClassLoader(), new Class<?>[] {IMSI.class},
                (p, m, a) -> "getData".equals(m.getName()) ? imsi : null);
        return (SendAuthenticationInfoRequest) Proxy.newProxyInstance(HssAppTest.class.getClassLoader(),
                new Class<?>[] {SendAuthenticationInfoRequest.class}, (p, m, a) -> switch (m.getName()) {
                    case "getImsi" -> i;
                    case "getNumberOfRequestedVectors" -> vectors;
                    case "getMapProtocolVersion" -> mapVersion;
                    case "getInvokeId" -> invokeId;
                    case "getMessageType" -> MAPMessageType.sendAuthenticationInfo_Request;
                    default -> m.getReturnType() == boolean.class ? false : null;
                });
    }

    /** An indexable SBB the container CAN auto-deploy: proves the test index is read. */
    public static final class ShadowProbeSbb implements Sbb {
        public ShadowProbeSbb() {}
    }

    /** In-process RA: fires events through the container's bootstrap port, records commands. */
    static final class FakeRa implements RaEndpointPort, RaCommandPort {
        private final String name;
        private final BlockingQueue<OutboundCommand> sent = new LinkedBlockingQueue<>();
        private volatile RaBootstrapPort bootstrap;

        FakeRa(String name) {
            this.name = name;
        }

        @Override public void activate(RaBootstrapPort b) { this.bootstrap = b; }
        @Override public void deactivate() { this.bootstrap = null; }
        @Override public String getRaName() { return name; }
        @Override public void sendCommand(OutboundCommand command) { sent.add(command); }

        void fire(String activity, SleeEvent event) {
            RaBootstrapPort b = bootstrap;
            assertNotNull(b, name + " not activated");
            b.fireEvent(event, b.createActivityHandle(activity), null);
        }

        OutboundCommand next() throws InterruptedException {
            OutboundCommand c = sent.poll(10, TimeUnit.SECONDS);
            assertNotNull(c, name + ": no command within 10 s");
            return c;
        }
    }
}
