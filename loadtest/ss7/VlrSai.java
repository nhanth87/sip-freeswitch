import com.microjainslee.ra.jss7.transport.Ss7Stack;
import java.lang.reflect.Proxy;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.restcomm.protocols.ss7.config.Ss7Config;
import org.restcomm.protocols.ss7.indicator.RoutingIndicator;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContext;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextName;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextVersion;
import org.restcomm.protocols.ss7.map.api.MAPDialogListener;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPDialogMobility;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPServiceMobilityListener;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.AuthenticationQuintuplet;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.SendAuthenticationInfoResponse;
import org.restcomm.protocols.ss7.sccp.impl.parameter.SccpAddressImpl;

/**
 * Lab VLR: one MAP SendAuthenticationInfo v3 (TS 29.002 §8.5.2) to the HLR face of the standalone
 * HSS over M3UA / SCTP, prints the quintuplets. The HSS's ra-jss7 always dials out, so this side
 * is the SCTP server and M3UA IPSP server.
 *
 * <pre>
 * java -Dsctp.backend=NETTY_KERNEL --add-modules jdk.sctp -cp "$CP" loadtest/ss7/VlrSai.java \
 *   127.0.0.1:2906 127.0.0.1:2905 2 1 001010000000009 [vectors]
 * </pre>
 * args: local ip:port, HSS ip:port, own PC (VLR), HLR PC, IMSI, vectors (default 1).
 * SSNs: VLR 7, HLR 6; routing context 100 / network appearance 102 (the HSS defaults).
 */
public final class VlrSai {

    public static void main(String[] a) throws Exception {
        String local = a[0], peer = a[1];
        int opc = Integer.parseInt(a[2]), dpc = Integer.parseInt(a[3]);
        String imsi = a[4];
        int vectors = a.length > 5 ? Integer.parseInt(a[5]) : 1;

        var link = new Ss7Config.Link("vlr-hlr", local, peer, List.of(), "sctp", "server", "vlr-srv", null, null);
        var sctp = new Ss7Config.Sctp(1000, 2, 256, 256, List.of(link), "NETTY_KERNEL", null, null, null, null);
        var as = new Ss7Config.As("AS1", "loadshare", "ipsp", "server", "se", 100L, 102L, 1, List.of("vlr-hlr"));
        var m3ua = new Ss7Config.M3ua(0, 2, List.of(as), List.of(new Ss7Config.Route(new Ss7Config.Dest(dpc, opc, 3), "AS1")));
        var any = new Ss7Config.Addr(null, null, "*", "GT0100", "even", "isdn", "international", null);
        var sccp = new Ss7Config.Sccp(
                List.of(new Ss7Config.LocalPoint(opc, "national", 0, List.of(dpc))),
                List.of(new Ss7Config.Rule("remote", 0, "K", any, new Ss7Config.Addr(opc, null, null, null, null, null, null, null), null),
                        new Ss7Config.Rule("local", 0, "K", any, new Ss7Config.Addr(dpc, null, null, null, null, null, null, null), null)));
        var tcap = new Ss7Config.Tcap(60_000, 30_000, 1000, 0, 0, false, false);
        var cfg = new Ss7Config("vlr", new Ss7Config.Protocols(true, false, false), sctp, m3ua, sccp, tcap,
                List.of(new Ss7Config.Service("vlr", 7, "map")));

        Ss7Stack stack = new Ss7Stack(cfg);
        stack.start();
        // Ss7StackBuilder derives remote SSNs from the local services only (VLR 7); the HLR is SSN 6.
        stack.underlying().sccpStack().getSccpResource().addRemoteSsn(100, dpc, 6, 0, false);
        long deadline = System.currentTimeMillis() + 60_000;
        while (!stack.isSignalingRouteReady() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        System.out.println("route ready=" + stack.isSignalingRouteReady() + " assoc=" + stack.associationDetails());
        if (!stack.isSignalingRouteReady()) {
            stack.stop();
            System.exit(2);
        }

        MAPProvider map = stack.mapProvider();
        CountDownLatch done = new CountDownLatch(1);
        int[] rc = {1};
        HexFormat hex = HexFormat.of();
        Object listener = Proxy.newProxyInstance(VlrSai.class.getClassLoader(),
                new Class<?>[] {MAPServiceMobilityListener.class, MAPDialogListener.class}, (p, m, args) -> {
                    switch (m.getName()) {
                        case "onSendAuthenticationInfoResponse" -> {
                            var r = (SendAuthenticationInfoResponse) args[0];
                            var list = r.getAuthenticationSetList();
                            var q = list == null || list.getQuintupletList() == null
                                    ? List.<AuthenticationQuintuplet>of()
                                    : list.getQuintupletList().getAuthenticationQuintuplets();
                            System.out.println("SAI result: " + q.size() + " quintuplet(s)");
                            for (AuthenticationQuintuplet v : q) {
                                System.out.println("  RAND=" + hex.formatHex(v.getRand()) + " XRES=" + hex.formatHex(v.getXres())
                                        + " AUTN=" + hex.formatHex(v.getAutn()));
                            }
                            rc[0] = q.isEmpty() ? 1 : 0;
                        }
                        case "onErrorComponent" -> System.out.println("MAP error: " + args[2]);
                        case "onRejectComponent" -> System.out.println("MAP reject: " + args[2]);
                        case "onInvokeTimeout" -> System.out.println("MAP invoke timeout");
                        case "onDialogUserAbort", "onDialogProviderAbort", "onDialogTimeout", "onDialogRelease",
                             "onDialogClose", "onDialogReject" -> {
                            System.out.println("dialog " + m.getName());
                            if (!"onDialogClose".equals(m.getName())) {
                                done.countDown();
                            }
                        }
                        case "hashCode" -> { return System.identityHashCode(p); }
                        case "equals" -> { return p == args[0]; }
                        case "toString" -> { return "VlrSaiListener"; }
                        default -> { }
                    }
                    return null;
                });
        map.addMAPDialogListener((MAPDialogListener) listener);
        map.getMAPServiceMobility().addMAPServiceListener((MAPServiceMobilityListener) listener);
        map.getMAPServiceMobility().activate();

        var calling = new SccpAddressImpl(RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN, null, opc, 7);
        var called = new SccpAddressImpl(RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN, null, dpc, 6);
        MAPDialogMobility d = map.getMAPServiceMobility().createNewDialog(
                MAPApplicationContext.getInstance(MAPApplicationContextName.infoRetrievalContext,
                        MAPApplicationContextVersion.version3), calling, null, called, null);
        d.addSendAuthenticationInfoRequest(map.getMAPParameterFactory().createIMSI(imsi), vectors,
                false, false, null, null, null, null, null, false, false);
        d.send();
        System.out.println("SAI sent imsi=" + imsi + " vectors=" + vectors);

        done.await(20, TimeUnit.SECONDS);
        stack.stop();
        System.exit(rc[0]);
    }
}
