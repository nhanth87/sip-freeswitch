package et.epc.sgwc;

import com.microjainslee.ra.gtpv2c.GtpFteid;
import com.microjainslee.ra.gtpv2c.Gtpv2Codec;
import com.microjainslee.ra.gtpv2c.Gtpv2Ie;
import com.microjainslee.ra.gtpv2c.Gtpv2Ies;
import com.microjainslee.ra.gtpv2c.Gtpv2Message;
import et.epc.api.Fteid;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * TS 29.274 message shapes shared by the SGW-C (S11 + S5-C client) and the PGW-C (S5-C server):
 * F-TEID interface types (§8.22), IE instances (Tables 7.2.1-x / 7.2.2-x) and the response
 * header TEID rule (§5.5.2: a response carries the requester's Sender F-TEID TEID).
 */
public final class GtpcShapes {

    /** §8.22 F-TEID interface types. */
    public static final int IF_S1U_ENB = 0, IF_S1U_SGW = 1, IF_S5U_SGW = 4, IF_S5U_PGW = 5,
            IF_S5C_SGW = 6, IF_S5C_PGW = 7, IF_S11_MME = 10, IF_S11_SGW = 11;

    /** §8.15 Bearer QoS, §8.21 ULI, §8.18 Serving Network, §8.17 RAT Type, §8.57 PDN Type … */
    public static final int MEI = 75, MSISDN = 76, AMBR = 72, BEARER_QOS = 80, RAT_TYPE = 82,
            SERVING_NETWORK = 83, ULI = 86, PDN_TYPE = 99, SELECTION_MODE = 128;

    /** IEs an SGW-C carries from the S11 Create Session Request onto S5-C (Table 7.2.1-1). */
    public static final Set<Integer> S5_PASS_THROUGH =
            Set.of(MSISDN, MEI, ULI, SERVING_NETWORK, RAT_TYPE, SELECTION_MODE, PDN_TYPE, AMBR, Gtpv2Ie.PCO);

    /** §8.4 Cause values used here. */
    public static final int CAUSE_ACCEPTED = 16, CAUSE_CONTEXT_NOT_FOUND = 64,
            CAUSE_MANDATORY_IE_MISSING = 70, CAUSE_NO_RESOURCES = 73, CAUSE_REMOTE_PEER_NOT_RESPONDING = 100;

    private GtpcShapes() {}

    public static Gtpv2Ie fteid(int instance, Fteid f) {
        return new Gtpv2Ie(Gtpv2Ie.FTEID, instance, Gtpv2Ies.encodeFteid(gtp(f)));
    }

    /** Bearer Context: EBI, optional Cause, then the given F-TEIDs (each with its instance). */
    public static Gtpv2Ie bearerContext(int ebi, Integer cause, List<Gtpv2Ie> inner) {
        List<Gtpv2Ie> ies = new ArrayList<>();
        ies.add(new Gtpv2Ie(Gtpv2Ie.EBI, 0, new byte[] {(byte) ebi}));
        if (cause != null) {
            ies.add(Gtpv2Ies.cause(cause));
        }
        ies.addAll(inner);
        return new Gtpv2Ie(Gtpv2Ie.BEARER_CONTEXT, 0, Gtpv2Codec.encodeIes(ies));
    }

    /** The F-TEID at {@code instance} at the top level, or null. */
    public static Fteid fteidAt(Gtpv2Message msg, int instance) {
        for (Gtpv2Ie ie : msg.ies()) {
            if (ie.type() == Gtpv2Ie.FTEID && ie.instance() == instance) {
                return api(Gtpv2Ies.decodeFteid(ie.value()));
            }
        }
        return null;
    }

    /** First Bearer Context's IEs, or an empty list. */
    public static List<Gtpv2Ie> firstBearer(Gtpv2Message msg) {
        Gtpv2Ie bc = msg.first(Gtpv2Ie.BEARER_CONTEXT);
        return bc == null ? List.of() : Gtpv2Ies.decodeGrouped(bc.value());
    }

    /** The F-TEID inside a Bearer Context with that interface type (instances vary by message). */
    public static Fteid bearerFteid(List<Gtpv2Ie> bearer, int interfaceType) {
        for (Gtpv2Ie ie : bearer) {
            if (ie.type() == Gtpv2Ie.FTEID) {
                GtpFteid f = Gtpv2Ies.decodeFteid(ie.value());
                if (f.interfaceType() == interfaceType) {
                    return api(f);
                }
            }
        }
        return null;
    }

    public static byte[] bearerIe(List<Gtpv2Ie> bearer, int type) {
        for (Gtpv2Ie ie : bearer) {
            if (ie.type() == type) {
                return ie.value();
            }
        }
        return null;
    }

    public static int cause(Gtpv2Message msg) {
        Gtpv2Ie c = msg.first(Gtpv2Ie.CAUSE);
        return c == null || c.value().length == 0 ? -1 : c.value()[0] & 0xff;
    }

    public static int ebi(Gtpv2Message msg, int fallback) {
        byte[] ebi = bearerIe(firstBearer(msg), Gtpv2Ie.EBI);
        if (ebi == null) {
            Gtpv2Ie top = msg.first(Gtpv2Ie.EBI);
            ebi = top == null ? null : top.value();
        }
        return ebi == null || ebi.length == 0 ? fallback : ebi[0] & 0x0f;
    }

    public static GtpFteid gtp(Fteid f) {
        return new GtpFteid(f.teid(), f.address(), f.interfaceType());
    }

    public static Fteid api(GtpFteid g) {
        return new Fteid(g.teid(), g.address(), g.interfaceType());
    }
}
