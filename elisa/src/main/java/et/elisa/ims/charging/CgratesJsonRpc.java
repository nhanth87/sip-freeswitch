package et.elisa.ims.charging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.atomic.AtomicLong;

/** Builds CGRates JSON-RPC 2.0 bodies (CDRsV1 + SessionS — no Camel). */
public final class CgratesJsonRpc {

    private static final AtomicLong IDS = new AtomicLong(1);

    private CgratesJsonRpc() {}

    public static ObjectNode processCdrRequest(ObjectMapper mapper, CgratesCdrEvent e) {
        ObjectNode root = mapper.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("method", "CDRsV1.ProcessCDR");
        root.put("id", IDS.getAndIncrement());
        ArrayNode params = root.putArray("params");
        params.add(cdrObject(mapper, e));
        return root;
    }

    public static ObjectNode initiateSessionRequest(ObjectMapper mapper, SessionSRequest e) {
        return sessionRequest(mapper, "SessionSv1.InitiateSession", e, true, false);
    }

    public static ObjectNode updateSessionRequest(ObjectMapper mapper, SessionSRequest e) {
        return sessionRequest(mapper, "SessionSv1.UpdateSession", e, false, false);
    }

    public static ObjectNode terminateSessionRequest(ObjectMapper mapper, SessionSRequest e) {
        return sessionRequest(mapper, "SessionSv1.TerminateSession", e, false, true);
    }

    private static ObjectNode sessionRequest(ObjectMapper mapper, String method, SessionSRequest e,
                                            boolean initiate, boolean terminate) {
        ObjectNode root = mapper.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("method", method);
        root.put("id", IDS.getAndIncrement());
        ArrayNode params = root.putArray("params");
        ObjectNode args = params.addObject();
        if (initiate) {
            args.put("InitSession", true);
            args.put("GetMaxUsage", true);
        }
        if (terminate) {
            args.put("TerminateSession", true);
            args.put("ReleaseResources", true);
        }
        if (!initiate && !terminate) {
            args.put("UpdateSession", true);
            args.put("GetMaxUsage", true);
        }
        ObjectNode cgr = args.putObject("CGREvent");
        cgr.put("Tenant", e.tenant());
        cgr.put("ID", e.originId() != null ? e.originId() : e.sipCallId());
        ObjectNode event = cgr.putObject("Event");
        event.put("Account", nullToEmpty(e.account()));
        event.put("Destination", nullToEmpty(e.destination()));
        event.put("Category", e.category());
        event.put("Source", e.source());
        event.put("RequestType", e.requestType());
        event.put("Usage", e.usage());
        if (e.originId() != null && !e.originId().isBlank()) {
            event.put("OriginID", e.originId());
        } else if (e.sipCallId() != null && !e.sipCallId().isBlank()) {
            event.put("OriginID", e.sipCallId());
        }
        if (e.setupTime() != null) {
            event.put("SetupTime", e.setupTime().toString());
        }
        if (e.answerTime() != null) {
            event.put("AnswerTime", e.answerTime().toString());
        }
        if (e.icid() != null && !e.icid().isBlank()) {
            event.put("icid", e.icid());
        }
        if (e.sipCallId() != null && !e.sipCallId().isBlank()) {
            event.put("sipCallId", e.sipCallId());
        }
        return root;
    }

    static ObjectNode cdrObject(ObjectMapper mapper, CgratesCdrEvent e) {
        ObjectNode cdr = mapper.createObjectNode();
        cdr.put("Tenant", e.tenant());
        cdr.put("Account", nullToEmpty(e.account()));
        cdr.put("Destination", nullToEmpty(e.destination()));
        cdr.put("Category", e.category());
        cdr.put("Source", e.source());
        cdr.put("RequestType", e.requestType());
        cdr.put("Usage", e.usage());
        if (e.originId() != null && !e.originId().isBlank()) {
            cdr.put("OriginID", e.originId());
        } else if (e.sipCallId() != null && !e.sipCallId().isBlank()) {
            cdr.put("OriginID", e.sipCallId());
        }
        if (e.setupTime() != null) {
            cdr.put("SetupTime", e.setupTime().toString());
        }
        if (e.answerTime() != null) {
            cdr.put("AnswerTime", e.answerTime().toString());
        }
        ObjectNode extra = cdr.putObject("ExtraFields");
        if (e.icid() != null && !e.icid().isBlank()) {
            extra.put("icid", e.icid());
        }
        if (e.sipCallId() != null && !e.sipCallId().isBlank()) {
            extra.put("sipCallId", e.sipCallId());
        }
        if (e.hangupCause() != null && !e.hangupCause().isBlank()) {
            extra.put("hangupCause", e.hangupCause());
        }
        return cdr;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
