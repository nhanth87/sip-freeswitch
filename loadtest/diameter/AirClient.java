import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.diameter.DiameterRaConfig;
import com.microjainslee.ra.diameter.DiameterRaEndpoint;
import com.microjainslee.ra.diameter.DiameterResourceAdaptor;
import com.microjainslee.ra.diameter.avp.DiameterAvp;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;
import com.microjainslee.ra.diameter.events.DiameterAnswerEvent;
import et.epc.crypto.Milenage;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Lab MME on S6a (TS 29.272 §5.2.3.1): AIR with Number-Of-Requested-Vectors, on micro-jainslee's
 * {@code ra-diameter}. For every E-UTRAN vector it recovers SQN = (AUTN[0..5] ⊕ AK), AK = f5(K, RAND),
 * and checks MAC-A (TS 33.102 §6.3) — so an AuC that hands two vectors the same SQN, or a vector
 * the UE would reject, shows up. Prints one {@code SQN <hex>} line per vector.
 *
 * <pre>
 * UE1_KI=.. UE1_OP=.. java -cp "$CP" loadtest/diameter/AirClient.java \
 *     &lt;hss ip&gt; &lt;hss origin-host&gt; &lt;client name&gt; &lt;imsi&gt; &lt;rounds&gt; &lt;vectors&gt;
 * </pre>
 * UE1_OP is the OPc (as in the RAN lab ue.env). Keys are read from the environment, never printed.
 */
public final class AirClient {

    static final long S6A = 16_777_251L;
    static final int AIR = 318;
    static final long V3GPP = 10_415L;
    static final HexFormat HEX = HexFormat.of();

    public static void main(String[] a) throws Exception {
        String hss = a[0];
        String hssHost = a[1];
        String name = a[2];
        String imsi = a[3];
        int rounds = Integer.parseInt(a[4]);
        int vectors = Integer.parseInt(a[5]);
        byte[] k = HEX.parseHex(System.getenv("UE1_KI"));
        byte[] opc = HEX.parseHex(System.getenv("UE1_OP"));

        DiameterRaConfig cfg = new DiameterRaConfig();
        cfg.setHost("0.0.0.0");
        cfg.setPort(Integer.parseInt(System.getenv().getOrDefault("AIR_LOCAL_PORT", "3870")));
        cfg.setOriginHost(name + ".epc.lab");
        cfg.setRealm("epc.lab");
        cfg.setProductName("air-client");
        cfg.setTcpEnabled(true);
        cfg.setSctpEnabled(false);
        cfg.setPeerRole("client");
        cfg.setPeers(DiameterRaConfig.parsePeers(hss + ":3868:client:" + hssHost + ":epc.lab:s6a"));
        cfg.setRequestTimeoutMs(5_000);
        DiameterRaEndpoint ra = new DiameterRaEndpoint(new DiameterResourceAdaptor());
        ra.setConfig(cfg);
        BlockingQueue<SleeEvent> in = new LinkedBlockingQueue<>();
        ra.activate(new RaBootstrapPort() {
            @Override public ActivityHandle createActivityHandle(String id) { return () -> id; }
            @Override public void fireEvent(SleeEvent e, ActivityHandle h, Address addr) { in.add(e); }
        });
        long deadline = System.currentTimeMillis() + 20_000;
        while (!ra.isPeerReady() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        if (!ra.isPeerReady()) {
            System.out.println("NO CEA from " + hss);
            ra.deactivate();
            System.exit(2);
        }
        int bad = 0;
        int seen = 0;
        for (int r = 0; r < rounds; r++) {
            String sid = name + ".epc.lab;air;" + r;
            ra.sendCommand(new SendDiameterRequest(sid, S6A, AIR, hssHost, "epc.lab", Map.of(), List.of(
                    DiameterAvp.u32(258, S6A),                                  // Auth-Application-Id
                    DiameterAvp.enum32(277, 1),                                 // NO_STATE_MAINTAINED
                    DiameterAvp.utf8(1, imsi),                                  // User-Name
                    DiameterAvp.octets(1407, V3GPP, new byte[] {0x00, (byte) 0xf1, 0x10}, true), // 001/01
                    DiameterAvp.grouped3gpp(1408, List.of(
                            DiameterAvp.u32(1410, V3GPP, vectors, true),
                            DiameterAvp.u32(1412, V3GPP, 1, true))))));
            DiameterAnswerEvent ans = awaitAnswer(in, sid);
            if (ans == null) {
                System.out.println("ROUND " + r + " no AIA");
                bad++;
                continue;
            }
            for (DiameterAvp v : vectorsOf(ans.avpsStructured())) {
                byte[] rand = leaf(v, 1447);
                byte[] autn = leaf(v, 1449);
                byte[] ak = Milenage.compute(k, opc, rand, new byte[6], new byte[] {autn[6], autn[7]}).ak();
                byte[] sqn = new byte[6];
                for (int i = 0; i < 6; i++) {
                    sqn[i] = (byte) (autn[i] ^ ak[i]);
                }
                byte[] mac = Milenage.compute(k, opc, rand, sqn, new byte[] {autn[6], autn[7]}).macA();
                boolean ok = java.util.Arrays.equals(mac, java.util.Arrays.copyOfRange(autn, 8, 16));
                System.out.println("SQN " + HEX.formatHex(sqn) + " " + name + " round=" + r + (ok ? " MAC-OK" : " MAC-BAD"));
                seen++;
                if (!ok) {
                    bad++;
                }
            }
        }
        System.out.println("DONE " + name + " vectors=" + seen + " bad=" + bad);
        ra.deactivate();
        System.exit(bad == 0 ? 0 : 1);
    }

    static DiameterAnswerEvent awaitAnswer(BlockingQueue<SleeEvent> in, String sid) throws InterruptedException {
        long end = System.currentTimeMillis() + 6_000;
        while (System.currentTimeMillis() < end) {
            SleeEvent e = in.poll(200, TimeUnit.MILLISECONDS);
            if (e instanceof DiameterAnswerEvent ans && sid.equals(ans.sessionId())) {
                if (ans.resultCode() != 2001) {
                    System.out.println("AIA result " + ans.resultCode());
                }
                return ans;
            }
        }
        return null;
    }

    /** Authentication-Info (1413) → E-UTRAN-Vector (1414) groups. */
    static List<DiameterAvp> vectorsOf(List<DiameterAvp> avps) {
        List<DiameterAvp> out = new ArrayList<>();
        for (DiameterAvp a : avps) {
            if (a.code() == 1413) {
                for (DiameterAvp v : a.children()) {
                    if (v.code() == 1414) {
                        out.add(v);
                    }
                }
            }
        }
        return out;
    }

    static byte[] leaf(DiameterAvp group, int code) {
        for (DiameterAvp c : group.children()) {
            if (c.code() == code) {
                return c.asOctets();
            }
        }
        throw new IllegalStateException("vector without AVP " + code);
    }
}
