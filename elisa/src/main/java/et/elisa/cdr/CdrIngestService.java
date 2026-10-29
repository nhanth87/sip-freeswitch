package et.elisa.cdr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import et.elisa.cluster.CallSession;
import et.elisa.ims.charging.ChargingDualWrite;
import et.elisa.ims.charging.PChargingVectors;
import et.elisa.ims.hss.HssLiteService;
import et.elisa.persist.ImsSubscriber;
import et.elisa.persist.SfCdr;
import et.elisa.persist.SfExtension;
import et.elisa.persist.SfTenant;
import et.elisa.session.CallSessionService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@ApplicationScoped
public class CdrIngestService {

    private static final Logger LOG = LogManager.getLogger(CdrIngestService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject CallSessionService callSessions;
    @Inject ChargingDualWrite chargingDualWrite;
    @Inject HssLiteService hss;
    /** Own client proxy so async ingest still runs inside {@code @Transactional}. */
    @Inject Provider<CdrIngestService> self;

    /**
     * SIP-dialog-end CDRs written off the S-CSCF BYE path (merge SELECT + INSERT + HSS
     * enrich per call). Bounded: a full queue runs the write on the caller thread
     * (backpressure) instead of dropping CDRs.
     */
    private final ThreadPoolExecutor sipDialogEndWriter = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(50_000),
            r -> {
                Thread t = new Thread(r, "elisa-cdr-writer");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    @PreDestroy
    void shutdown() {
        sipDialogEndWriter.shutdown();
        try {
            sipDialogEndWriter.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** {@link #ingestSipDialogEnd} on the CDR writer pool; never blocks the SBB unless saturated. */
    public void ingestSipDialogEndAsync(String sipCallId, String fromUri, String toUri, String msisdn,
                                        String icid, String chargingVector, String impi, String impu,
                                        String cscfRole, Integer durationSec) {
        CdrIngestService tx = self != null ? self.get() : this;
        sipDialogEndWriter.execute(() -> {
            try {
                tx.ingestSipDialogEnd(sipCallId, fromUri, toUri, msisdn, icid, chargingVector,
                        impi, impu, cscfRole, durationSec);
            } catch (RuntimeException ex) {
                LOG.warn("SIP dialog CDR write failed callId={}: {}", sipCallId, ex.toString());
            }
        });
    }

    @Transactional
    public SfCdr ingest(JsonNode cdr) {
        Instant now = Instant.now();
        String sipHint = text(cdr, "variable_sip_call_id",
                text(cdr, "variable_elisa_sip_call_id",
                        text(cdr, "sip_call_id", null)));
        SfCdr row = findMergeCandidate(sipHint, text(cdr, "uuid", text(cdr, "variable_uuid", null)))
                .orElseGet(SfCdr::new);
        boolean fresh = row.id == null;
        if (fresh) {
            row.dateCreated = now;
            row.createdAt = now;
            row.apiVersion = "elisa";
            row.uri = "";
        }
        row.dateUpdated = now;
        row.callUuid = text(cdr, "uuid", text(cdr, "variable_uuid", row.callUuid));
        row.parentCallUuid = text(cdr, "variable_originating_leg_uuid",
                text(cdr, "parent_call_uuid", row.parentCallUuid));
        row.payload = cdr.toString();
        row.sender = text(cdr, "caller_id_number", text(cdr, "variable_sip_from_user",
                text(cdr, "from", row.sender != null ? row.sender : "")));
        row.recipient = text(cdr, "destination_number", text(cdr, "variable_sip_to_user",
                text(cdr, "to", row.recipient != null ? row.recipient : "")));
        row.callerName = text(cdr, "caller_id_name", row.callerName);
        row.status = text(cdr, "hangup_cause", text(cdr, "variable_endpoint_disposition",
                row.status != null ? row.status : "completed"));
        row.hangupCause = text(cdr, "hangup_cause", row.hangupCause);
        row.direction = text(cdr, "direction", text(cdr, "Call-Direction",
                row.direction != null ? row.direction : "unknown"));
        Integer dur = intVal(cdr, "variable_duration", intVal(cdr, "billsec", null));
        if (dur != null) {
            row.duration = dur;
        }
        Integer ring = intVal(cdr, "variable_progresssec", null);
        if (ring != null) {
            row.ringDuration = ring;
        }
        Instant start = parseTime(text(cdr, "start_epoch", text(cdr, "variable_start_epoch", null)), null);
        if (start != null) {
            row.startTime = start;
        } else if (row.startTime == null) {
            row.startTime = now;
        }
        Instant end = parseTime(text(cdr, "end_epoch", text(cdr, "variable_end_epoch", null)), null);
        row.endTime = end != null ? end : now;
        row.instanceId = text(cdr, "FreeSWITCH-Hostname", row.instanceId);
        row.callPath = text(cdr, "variable_sofia_profile_name", row.callPath);
        row.callType = text(cdr, "variable_call_type", row.callType);

        String tenantId = text(cdr, "variable_tenant_id", text(cdr, "variable_sip_h_X-Tenant", null));
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = lookupExtensionTenant(row.sender);
            if (tenantId == null) {
                tenantId = lookupExtensionTenant(row.recipient);
            }
        }
        String trunkTenant = text(cdr, "variable_trunk_tenant_id", null);
        if (blankToNull(tenantId) != null) {
            row.tenantId = blankToNull(tenantId);
            row.accountSid = row.tenantId;
        }
        if (blankToNull(trunkTenant) != null) {
            row.trunkTenantId = blankToNull(trunkTenant);
        }

        if (row.callUuid != null && !row.callUuid.isBlank() && callSessions != null) {
            callSessions.findByFsUuid(row.callUuid).ifPresent(s -> applySession(row, s));
        }
        if ((row.sipCallId == null || row.sipCallId.isBlank())) {
            row.sipCallId = blankToNull(sipHint);
        }
        if ((row.msisdn == null || row.msisdn.isBlank())) {
            row.msisdn = text(cdr, "variable_msisdn", null);
        }

        String pcv = text(cdr, "variable_sip_h_p-charging-vector",
                text(cdr, "variable_sip_h_P-Charging-Vector",
                        text(cdr, "variable_elisa_charging_vector", null)));
        applyChargingVector(row, pcv);
        if (row.icid == null || row.icid.isBlank()) {
            row.icid = blankToNull(text(cdr, "variable_elisa_icid",
                    text(cdr, "variable_sip_h_X-Elisa-Icid", null)));
        }
        if ((row.icid == null || row.icid.isBlank()) && row.sipCallId != null) {
            row.icid = PChargingVectors.synthesizeIcid(row.sipCallId);
        }
        if (row.cscfRole == null || row.cscfRole.isBlank()) {
            row.cscfRole = text(cdr, "variable_elisa_cscf_role", "S");
        }
        row.impi = firstNonBlank(row.impi, text(cdr, "variable_elisa_impi", null));
        row.impu = firstNonBlank(row.impu, text(cdr, "variable_elisa_impu", null));
        enrichFromHss(row);

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
        if (fresh) {
            row.persist();
        }
        try {
            if (chargingDualWrite != null) {
                chargingDualWrite.afterCdrPersist(row);
            }
        } catch (Throwable t) {
            LOG.warn("CDR charging dual-write soft-fail uuid={}: {}", row.callUuid, t.toString());
        }
        return row;
    }

    /**
     * SIP-dialog-end CDR (Clearwater-CCF-equivalent without waiting for FS hangup).
     * Merges with an existing FS row for the same {@code sipCallId} when present.
     */
    @Transactional
    public SfCdr ingestSipDialogEnd(String sipCallId, String fromUri, String toUri, String msisdn,
                                    String icid, String chargingVector, String impi, String impu,
                                    String cscfRole, Integer durationSec) {
        Instant now = Instant.now();
        SfCdr row = findMergeCandidate(sipCallId, null).orElseGet(SfCdr::new);
        boolean fresh = row.id == null;
        if (fresh) {
            row.dateCreated = now;
            row.createdAt = now;
            row.apiVersion = "elisa";
            row.uri = "";
            row.callUuid = "sip:" + (sipCallId != null ? sipCallId : now.toEpochMilli());
            row.status = "sip-bye";
            row.direction = "ims";
            row.startTime = now.minusSeconds(durationSec != null ? Math.max(0, durationSec) : 0);
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("source", "sip-dialog");
            payload.put("sip_call_id", sipCallId != null ? sipCallId : "");
            row.payload = payload.toString();
        }
        row.dateUpdated = now;
        row.endTime = now;
        if (durationSec != null) {
            row.duration = durationSec;
        }
        row.sipCallId = firstNonBlank(row.sipCallId, sipCallId);
        row.sender = firstNonBlank(row.sender, fromUri);
        row.recipient = firstNonBlank(row.recipient, toUri);
        row.msisdn = firstNonBlank(row.msisdn, msisdn);
        row.impi = firstNonBlank(row.impi, impi);
        row.impu = firstNonBlank(row.impu, impu);
        row.cscfRole = firstNonBlank(row.cscfRole, cscfRole != null ? cscfRole : "S");
        applyChargingVector(row, chargingVector);
        if (row.icid == null || row.icid.isBlank()) {
            row.icid = blankToNull(icid);
        }
        if ((row.icid == null || row.icid.isBlank()) && row.sipCallId != null) {
            row.icid = PChargingVectors.synthesizeIcid(row.sipCallId);
        }
        enrichFromHss(row);
        if (fresh) {
            row.persist();
        }
        return row;
    }

    private Optional<SfCdr> findMergeCandidate(String sipCallId, String callUuid) {
        try {
            if (callUuid != null && !callUuid.isBlank()) {
                SfCdr byUuid = SfCdr.find("callUuid", callUuid).firstResult();
                if (byUuid != null) {
                    return Optional.of(byUuid);
                }
            }
            if (sipCallId != null && !sipCallId.isBlank()) {
                SfCdr bySip = SfCdr.find("sipCallId", sipCallId).firstResult();
                if (bySip != null) {
                    return Optional.of(bySip);
                }
            }
        } catch (RuntimeException ex) {
            // Unit / no Panache
        }
        return Optional.empty();
    }

    private void enrichFromHss(SfCdr row) {
        if (hss == null) {
            return;
        }
        try {
            Optional<ImsSubscriber> sub = Optional.empty();
            if (row.impu != null && !row.impu.isBlank()) {
                sub = hss.findByImpu(row.impu);
            }
            if (sub.isEmpty() && row.sender != null) {
                sub = hss.resolveIdentity(row.sender);
            }
            if (sub.isEmpty() && row.msisdn != null) {
                sub = hss.resolveIdentity(row.msisdn);
            }
            sub.ifPresent(s -> {
                if (row.impi == null || row.impi.isBlank()) {
                    row.impi = s.impi;
                }
                if (row.impu == null || row.impu.isBlank()) {
                    row.impu = s.impu;
                }
                if ((row.msisdn == null || row.msisdn.isBlank()) && s.msisdn != null) {
                    row.msisdn = s.msisdn;
                }
                if ((row.tenantId == null || row.tenantId.isBlank()) && s.tenantId != null) {
                    row.tenantId = s.tenantId;
                    row.accountSid = s.tenantId;
                }
            });
        } catch (RuntimeException ex) {
            LOG.debug("CDR HSS enrich soft-fail: {}", ex.toString());
        }
    }

    private static void applyChargingVector(SfCdr row, String pcv) {
        String vector = blankToNull(pcv);
        if (vector != null) {
            row.chargingVector = vector;
            if (row.icid == null || row.icid.isBlank()) {
                row.icid = blankToNull(PChargingVectors.extractIcidValue(vector));
            }
            if (row.origIoi == null || row.origIoi.isBlank()) {
                row.origIoi = blankToNull(PChargingVectors.extractOrigIoi(vector));
            }
            if (row.termIoi == null || row.termIoi.isBlank()) {
                row.termIoi = blankToNull(PChargingVectors.extractTermIoi(vector));
            }
        }
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
        try {
            SfExtension ext = SfExtension.find("extension", number.trim()).firstResult();
            return ext == null ? null : ext.tenantId;
        } catch (RuntimeException ex) {
            return null;
        }
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

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return blankToNull(b);
    }
}
