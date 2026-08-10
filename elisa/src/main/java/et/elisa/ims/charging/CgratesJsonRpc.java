package et.elisa.ims.charging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.atomic.AtomicLong;

/** Builds CGRates JSON-RPC 2.0 bodies (no Camel). */
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
