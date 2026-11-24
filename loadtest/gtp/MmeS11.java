import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.gtpv2c.GtpFteid;
import com.microjainslee.ra.gtpv2c.Gtpv2Codec;
import com.microjainslee.ra.gtpv2c.Gtpv2Ie;
import com.microjainslee.ra.gtpv2c.Gtpv2Ies;
import com.microjainslee.ra.gtpv2c.Gtpv2Message;
import com.microjainslee.ra.gtpv2c.Gtpv2MessageType;
import com.microjainslee.ra.gtpv2c.Gtpv2RaEndpoint;
import com.microjainslee.ra.gtpv2c.command.SendGtpv2Message;
import com.microjainslee.ra.gtpv2c.event.Gtpv2MessageEvent;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Lab MME on S11 (TS 29.274): Create Session → Modify Bearer (eNB S1-U) → Delete Session against
 * an SGW-C, over micro-jainslee's {@code ra-gtpv2c}. Prints every answer's IEs.
 *
 * <pre>
 * java -cp "$CP" loadtest/gtp/MmeS11.java &lt;local ip&gt; &lt;sgwc ip&gt; [imsi] [apn] [enb ip]
 * </pre>
 */
public final class MmeS11 {

    static final HexFormat HEX = HexFormat.of();
    /** TS 29.274 §8.22 F-TEID interface types. */
    static final int S11_MME = 10, S1U_ENB = 0;
    /** TS 29.274 §8.15 Bearer QoS, §8.17 RAT Type, §8.18 Serving Network, §8.57 PDN Type. */
    static final int BEARER_QOS = 80, RAT_TYPE = 82, SERVING_NETWORK = 83, PDN_TYPE = 99, MSISDN = 76;

    public static void main(String[] a) throws Exception {
        InetAddress local = InetAddress.getByName(a[0]);
        InetSocketAddress sgwc = new InetSocketAddress(a[1], 2123);
        String imsi = a.length > 2 ? a[2] : "001010000000009";
        String apn = a.length > 3 ? a[3] : "internet";
        InetAddress enb = InetAddress.getByName(a.length > 4 ? a[4] : a[0]);

        BlockingQueue<Gtpv2Message> in = new LinkedBlockingQueue<>();
        Gtpv2RaEndpoint gtp = new Gtpv2RaEndpoint();
        gtp.activate(new RaBootstrapPort() {
            @Override public ActivityHandle createActivityHandle(String id) { return () -> id; }
            @Override public void fireEvent(SleeEvent e, ActivityHandle h, Address addr) {
                if (e instanceof Gtpv2MessageEvent m) {
                    in.add(m.message());
                }
            }
        });
        gtp.bindUdp(new InetSocketAddress(local, 2123));

        int mmeTeid = 0x0000A001;
        // Bearer Context to be created: EBI 5 + Bearer QoS (QCI 9, ARP 8, no MBR/GBR).
        byte[] qos = new byte[22];
        qos[0] = (byte) (8 << 2);
        qos[1] = 9;
        byte[] bearer = Gtpv2Codec.encodeIes(List.of(
                new Gtpv2Ie(Gtpv2Ie.EBI, 0, new byte[] {5}),
                new Gtpv2Ie(BEARER_QOS, 0, qos)));
        Gtpv2Message csr = new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_REQUEST, 0, 1, (byte) 1, List.of(
                new Gtpv2Ie(Gtpv2Ie.IMSI, 0, Gtpv2Ies.tbcd(imsi)),
                new Gtpv2Ie(MSISDN, 0, Gtpv2Ies.tbcd("251911000009")),
                new Gtpv2Ie(RAT_TYPE, 0, new byte[] {6}),                        // EUTRAN
                new Gtpv2Ie(SERVING_NETWORK, 0, new byte[] {0x00, (byte) 0xf1, 0x10}), // 001/01
                new Gtpv2Ie(Gtpv2Ie.FTEID, 0, Gtpv2Ies.encodeFteid(new GtpFteid(mmeTeid, local, S11_MME))),
                new Gtpv2Ie(Gtpv2Ie.APN, 0, Gtpv2Ies.encodeApn(apn)),
                new Gtpv2Ie(PDN_TYPE, 0, new byte[] {1}),                        // IPv4
                new Gtpv2Ie(Gtpv2Ie.BEARER_CONTEXT, 0, bearer)));

        long t0 = System.nanoTime();
        gtp.sendCommand(new SendGtpv2Message(csr, sgwc));
        Gtpv2Message csResp = await(in, Gtpv2MessageType.CREATE_SESSION_RESPONSE, t0);
        if (csResp == null) {
            gtp.deactivate();
            System.exit(1);
        }
        if (cause(csResp) != Gtpv2Ies.CAUSE_REQUEST_ACCEPTED) {
            gtp.deactivate();
            System.exit(1);
        }
        GtpFteid sgwS11 = Gtpv2Ies.senderFteid(csResp);
        if (sgwS11 == null) {
            gtp.deactivate();
            System.exit(1);
        }

        // Modify Bearer: the eNB's S1-U F-TEID (TS 29.274 §7.2.7).
        byte[] mbBearer = Gtpv2Ies.encodeBearerContext((byte) 5, new GtpFteid(0x0000E001, enb, S1U_ENB));
        Gtpv2Message mbr = new Gtpv2Message(Gtpv2MessageType.MODIFY_BEARER_REQUEST, sgwS11.teid(), 2, (byte) 1,
                List.of(new Gtpv2Ie(Gtpv2Ie.BEARER_CONTEXT, 0, mbBearer)));
        long t1 = System.nanoTime();
        gtp.sendCommand(new SendGtpv2Message(mbr, sgwc));
        await(in, Gtpv2MessageType.MODIFY_BEARER_RESPONSE, t1);

        Thread.sleep(1_000);
        Gtpv2Message dsr = new Gtpv2Message(Gtpv2MessageType.DELETE_SESSION_REQUEST, sgwS11.teid(), 3, (byte) 1,
                List.of(new Gtpv2Ie(Gtpv2Ie.EBI, 0, new byte[] {5})));
        long t2 = System.nanoTime();
        gtp.sendCommand(new SendGtpv2Message(dsr, sgwc));
        Gtpv2Message dsResp = await(in, Gtpv2MessageType.DELETE_SESSION_RESPONSE, t2);
        gtp.deactivate();
        System.exit(dsResp != null && cause(dsResp) == Gtpv2Ies.CAUSE_REQUEST_ACCEPTED ? 0 : 1);
    }

    static Gtpv2Message await(BlockingQueue<Gtpv2Message> in, Gtpv2MessageType want, long sentNanos)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Gtpv2Message m = in.poll(200, TimeUnit.MILLISECONDS);
            if (m == null) {
                continue;
            }
            long ms = (System.nanoTime() - sentNanos) / 1_000_000;
            System.out.println(m.type() + " teid=0x" + Integer.toHexString(m.teid()) + " seq=" + m.sequence()
                    + " after " + ms + " ms");
            for (Gtpv2Ie ie : m.ies()) {
                System.out.println("  " + describe(ie, "  "));
            }
            if (m.type() == want) {
                return m;
            }
        }
        System.out.println("NO " + want + " within 15 s");
        return null;
    }

    static int cause(Gtpv2Message m) {
        Gtpv2Ie c = m.first(Gtpv2Ie.CAUSE);
        return c == null || c.value().length == 0 ? -1 : c.value()[0] & 0xff;
    }

    static String describe(Gtpv2Ie ie, String indent) {
        return switch (ie.type()) {
            case Gtpv2Ie.CAUSE -> "Cause=" + (ie.value()[0] & 0xff);
            case Gtpv2Ie.FTEID -> {
                GtpFteid f = Gtpv2Ies.decodeFteid(ie.value());
                yield "F-TEID[" + ie.instance() + "] if=" + f.interfaceType() + " teid=0x"
                        + Integer.toHexString(f.teid()) + " " + f.address().getHostAddress();
            }
            case Gtpv2Ie.PDN_ADDRESS -> "PAA=" + HEX.formatHex(ie.value());
            case Gtpv2Ie.BEARER_CONTEXT -> {
                StringBuilder sb = new StringBuilder("Bearer-Context[" + ie.instance() + "]");
                for (Gtpv2Ie inner : Gtpv2Ies.decodeGrouped(ie.value())) {
                    sb.append("\n").append(indent).append("  ").append(describe(inner, indent + "  "));
                }
                yield sb.toString();
            }
            case Gtpv2Ie.EBI -> "EBI=" + (ie.value()[0] & 0x0f);
            case Gtpv2Ie.PCO -> "PCO " + Gtpv2Ies.decodePcoValue(ie.value());
            case Gtpv2Ie.CHARGING_ID -> "Charging-ID=0x" + HEX.formatHex(ie.value());
            default -> "IE " + ie.type() + "[" + ie.instance() + "]=" + HEX.formatHex(ie.value());
        };
    }
}
