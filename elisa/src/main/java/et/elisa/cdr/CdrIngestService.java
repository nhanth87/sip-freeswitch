package et.elisa.cdr;

import com.fasterxml.jackson.databind.JsonNode;
import et.elisa.ims.charging.PChargingVectors;
import et.elisa.persist.SfCdr;
import et.elisa.persist.SfExtension;
import et.elisa.persist.SfTenant;
import et.elisa.session.CallSessionService;
import et.elisa.cluster.CallSession;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

@ApplicationScoped
public class CdrIngestService {

    @Inject CallSessionService callSessions;

    @Transactional
    public SfCdr ingest(JsonNode cdr) {
        Instant now = Instant.now();
        SfCdr row = new SfCdr();
        row.callUuid = text(cdr, "uuid", text(cdr, "variable_uuid", ""));
        row.parentCallUuid = text(cdr, "variable_originating_leg_uuid", text(cdr, "parent_call_uuid", null));
        row.payload = cdr.toString();
        row.createdAt = now;
        row.dateCreated = now;
        row.dateUpdated = now;
        row.apiVersion = "elisa";
        row.uri = "";
        row.sender = text(cdr, "caller_id_number", text(cdr, "variable_sip_from_user", text(cdr, "from", "")));
        row.recipient = text(cdr, "destination_number", text(cdr, "variable_sip_to_user", text(cdr, "to", "")));
        row.callerName = text(cdr, "caller_id_name", null);
        row.status = text(cdr, "hangup_cause", text(cdr, "variable_endpoint_disposition", "completed"));
        row.hangupCause = text(cdr, "hangup_cause", null);
        row.direction = text(cdr, "direction", text(cdr, "Call-Direction", "unknown"));
        row.duration = intVal(cdr, "variable_duration", intVal(cdr, "billsec", null));
        row.ringDuration = intVal(cdr, "variable_progresssec", null);
        row.startTime = parseTime(text(cdr, "start_epoch", text(cdr, "variable_start_epoch", null)), now);
        row.endTime = parseTime(text(cdr, "end_epoch", text(cdr, "variable_end_epoch", null)), now);
        row.instanceId = text(cdr, "FreeSWITCH-Hostname", null);
        row.callPath = text(cdr, "variable_sofia_profile_name", null);
        row.callType = text(cdr, "variable_call_type", null);

        String tenantId = text(cdr, "variable_tenant_id", text(cdr, "variable_sip_h_X-Tenant", null));
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = lookupExtensionTenant(row.sender);
            if (tenantId == null) {
                tenantId = lookupExtensionTenant(row.recipient);
            }
        }
        String trunkTenant = text(cdr, "variable_trunk_tenant_id", null);
        row.tenantId = blankToNull(tenantId);
        row.trunkTenantId = blankToNull(trunkTenant);
        row.accountSid = row.tenantId;

        // P5: enrich from dual-write call session
        if (row.callUuid != null && !row.callUuid.isBlank() && callSessions != null) {
            callSessions.findByFsUuid(row.callUuid).ifPresent(s -> applySession(row, s));
        }
        if ((row.sipCallId == null || row.sipCallId.isBlank())) {
            row.sipCallId = text(cdr, "variable_sip_call_id",
                    text(cdr, "variable_elisa_sip_call_id", null));
        }
        if ((row.msisdn == null || row.msisdn.isBlank())) {
            row.msisdn = text(cdr, "variable_msisdn", null);
        }
        if (row.icid == null || row.icid.isBlank()) {
            String pcv = text(cdr, "variable_sip_h_p-charging-vector",
                    text(cdr, "variable_sip_h_P-Charging-Vector", null));
            row.icid = blankToNull(PChargingVectors.extractIcidValue(pcv));
            if (row.icid == null) {
                row.icid = blankToNull(text(cdr, "variable_elisa_icid",
                        text(cdr, "variable_sip_h_X-Elisa-Icid", null)));
            }
        }

        if (row.tenantId != null) {
            SfTenant t = SfTenant.findById(row.tenantId);
            if (t != null) {
                row.resellerId = t.resellerId;
                row.sourceAddr = t.sourceAddrAlias != null && !t.sourceAddrAlias.isBlank()
                        ? t.sourceAddrAlias : t.id;
            }
        }
        if (row.trunkTenantId != null && row.resellerId == null) {
            SfTenant trunk = SfTenant.findById(row.trunkTenantId);
            if (trunk != null) {
                row.resellerId = trunk.resellerId;
            }
        }
        row.persist();
        return row;
    }

    private static void applySession(SfCdr row, CallSession s) {
        if (s.sipCallId() != null) {
            row.sipCallId = s.sipCallId();
        }
        if (s.msisdn() != null) {
            row.msisdn = s.msisdn();
        }
        if ((row.tenantId == null || row.tenantId.isBlank()) && s.tenantId() != null) {
            row.tenantId = s.tenantId();
            row.accountSid = s.tenantId();
        }
    }

    private static String lookupExtensionTenant(String number) {
        if (number == null || number.isBlank()) {
            return null;
        }
        SfExtension ext = SfExtension.find("extension", number.trim()).firstResult();
        return ext == null ? null : ext.tenantId;
    }

    private static String text(JsonNode n, String field, String def) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return def;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? def : s;
    }

    private static Integer intVal(JsonNode n, String field, Integer def) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return def;
        }
        if (v.isInt() || v.isLong()) {
            return v.asInt();
        }
        try {
            return Integer.parseInt(v.asText().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Instant parseTime(String raw, Instant fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            long epoch = Long.parseLong(raw.trim());
            if (epoch > 1_000_000_000_000L) {
                return Instant.ofEpochMilli(epoch);
            }
            return Instant.ofEpochSecond(epoch);
        } catch (NumberFormatException ignored) {
            try {
                return OffsetDateTime.parse(raw).toInstant();
            } catch (DateTimeParseException e) {
                return fallback;
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
