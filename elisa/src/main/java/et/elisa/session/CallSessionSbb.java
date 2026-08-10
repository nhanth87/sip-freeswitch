package et.elisa.session;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.CmpField;
import com.microjainslee.api.annotations.SbbAnnotation;
import com.microjainslee.core.CmpBackedSbb;
import et.elisa.logging.SleeEventTrace;
import et.elisa.ra.freeswitch.events.FsApiResponseEvent;
import et.elisa.ra.freeswitch.events.FsChannelEvent;
import et.elisa.ra.freeswitch.events.FsHeartbeatEvent;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * ESL channel lifecycle → CallSession dual-write (no body dumps).
 * CMP fields hold the live FS↔SIP correlate so a recreated SBB entity can
 * continue CHANNEL_* / hangup handling after restore from ISPN/PG via
 * {@link CallSessionService}.
 */
@SbbAnnotation(name = "CallSessionSbb", vendor = "et.elisa", version = "1.0")
public abstract class CallSessionSbb extends CmpBackedSbb implements SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(CallSessionSbb.class);

    private final CallSessionService sessions;
    private final String defaultFsNodeId;

    public CallSessionSbb() {
        this(null, "fallback");
    }

    public CallSessionSbb(CallSessionService sessions, String fsNodeId) {
        this.sessions = sessions;
        this.defaultFsNodeId = fsNodeId == null ? "fallback" : fsNodeId;
    }

    @CmpField("fsUuid")
    public abstract String getFsUuid();

    @CmpField("fsUuid")
    public abstract void setFsUuid(String fsUuid);

    @CmpField("sipCallId")
    public abstract String getSipCallId();

    @CmpField("sipCallId")
    public abstract void setSipCallId(String sipCallId);

    @CmpField("msisdn")
    public abstract String getMsisdn();

    @CmpField("msisdn")
    public abstract void setMsisdn(String msisdn);

    @CmpField("fromUri")
    public abstract String getFromUri();

    @CmpField("fromUri")
    public abstract void setFromUri(String fromUri);

    @CmpField("toUri")
    public abstract String getToUri();

    @CmpField("toUri")
    public abstract void setToUri(String toUri);

    @CmpField("fsNodeId")
    public abstract String getFsNodeId();

    @CmpField("fsNodeId")
    public abstract void setFsNodeId(String fsNodeId);

    @CmpField("elisaNodeId")
    public abstract String getElisaNodeId();

    @CmpField("elisaNodeId")
    public abstract void setElisaNodeId(String elisaNodeId);

    @CmpField("dialogState")
    public abstract String getDialogState();

    @CmpField("dialogState")
    public abstract void setDialogState(String dialogState);

    @CmpField("direction")
    public abstract String getDirection();

    @CmpField("direction")
    public abstract void setDirection(String direction);

    @CmpField("startedAtEpochMs")
    public abstract Long getStartedAtEpochMs();

    @CmpField("startedAtEpochMs")
    public abstract void setStartedAtEpochMs(Long startedAtEpochMs);

    @CmpField("updatedAtEpochMs")
    public abstract Long getUpdatedAtEpochMs();

    @CmpField("updatedAtEpochMs")
    public abstract void setUpdatedAtEpochMs(Long updatedAtEpochMs);

    @Override public void sbbCreate() {}

    @Override
    public void sbbActivate() {
        String uuid = getFsUuid();
        if (uuid != null && !uuid.isBlank() && sessions != null) {
            sessions.findByFsUuid(uuid).ifPresent(this::applySession);
        }
    }

    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        SleeEventTrace.inSbb("CallSessionSbb", event);
        try {
            switch (event) {
                case FsChannelEvent e -> onChannel(e);
                case FsHeartbeatEvent e ->
                        LOG.debug("[FS] HEARTBEAT uptime={}", e.headers().getOrDefault("Up-Time", "?"));
                case FsApiResponseEvent e ->
                        LOG.debug("[FS] api requestId={} ok={} live={}", e.requestId(), e.ok(), e.live());
                default -> {}
            }
            SleeEventTrace.outSbb("CallSessionSbb", event, null);
        } catch (Throwable t) {
            LOG.error("[CallSessionSbb] onEvent failed: {}", t.toString(), t);
            SleeEventTrace.outSbb("CallSessionSbb", event, "error=" + t.getClass().getSimpleName());
        }
    }

    void onChannel(FsChannelEvent e) {
        if (sessions == null) {
            return;
        }
        String name = e.eventName() == null ? "" : e.eventName();
        String uuid = e.uuid();
        Map<String, String> h = e.headers();
        if (name.contains("HANGUP") || name.contains("DESTROY")) {
            ensureCmpFromStore(uuid);
            sessions.endByFsUuid(uuid, "HANGUP");
            setDialogState("HANGUP");
            setUpdatedAtEpochMs(System.currentTimeMillis());
            LOG.debug("[FS] {} uuid={}", name, uuid);
            return;
        }
        if (name.contains("CREATE") || name.contains("ANSWER") || name.contains("BRIDGE")
                || name.contains("PROGRESS")) {
            String sip = first(h,
                    "variable_sip_call_id",
                    "variable_sip_h_Call-ID",
                    "variable_elisa_sip_call_id",
                    "Caller-Unique-ID");
            String caller = first(h,
                    "Caller-Caller-ID-Number",
                    "Caller-Username",
                    "variable_sip_from_user",
                    "Caller-ANI");
            String callee = first(h,
                    "Caller-Destination-Number",
                    "Caller-Callee-ID-Number",
                    "variable_sip_to_user");
            String direction = first(h, "Call-Direction", "Caller-Direction");
            String state = name.contains("ANSWER") ? "ANSWERED"
                    : name.contains("BRIDGE") ? "BRIDGED"
                    : name.contains("PROGRESS") ? "PROGRESS" : "CREATED";
            var session = sessions.upsertFromFs(uuid, sip, caller, callee, direction, state, defaultFsNodeId);
            if (session != null) {
                applySession(session);
            } else {
                setFsUuid(uuid);
                setSipCallId(sip);
                setFromUri(caller);
                setToUri(callee);
                setDirection(direction);
                setDialogState(state);
                setFsNodeId(defaultFsNodeId);
                setUpdatedAtEpochMs(System.currentTimeMillis());
            }
            LOG.debug("[FS] {} uuid={} sip={}", name, uuid, sip);
        }
    }

    /**
     * Simulate entity death: clear CMP and reload from CallSessionService (ISPN→PG).
     * @return true when a live session was restored
     */
    public boolean recoverAfterFailure(String fsUuid) {
        clearLocalCmp();
        if (sessions == null || fsUuid == null) {
            return false;
        }
        return sessions.findByFsUuid(fsUuid).map(s -> {
            applySession(s);
            return true;
        }).orElse(false);
    }

    private void ensureCmpFromStore(String fsUuid) {
        if (fsUuid == null) {
            return;
        }
        String current = getFsUuid();
        if (current == null || current.isBlank() || !fsUuid.equals(current)) {
            sessions.findByFsUuid(fsUuid).ifPresent(this::applySession);
        }
    }

    private void applySession(et.elisa.cluster.CallSession s) {
        setFsUuid(s.fsUuid());
        setSipCallId(s.sipCallId());
        setMsisdn(s.msisdn());
        setFsNodeId(s.fsNodeId());
        setElisaNodeId(s.elisaNodeId());
        setDirection(s.direction());
        setDialogState(s.state());
        if (s.startedAt() != null) {
            setStartedAtEpochMs(s.startedAt().toEpochMilli());
        }
        if (s.updatedAt() != null) {
            setUpdatedAtEpochMs(s.updatedAt().toEpochMilli());
        }
    }

    private void clearLocalCmp() {
        setFsUuid(null);
        setSipCallId(null);
        setMsisdn(null);
        setFromUri(null);
        setToUri(null);
        setFsNodeId(null);
        setElisaNodeId(null);
        setDialogState(null);
        setDirection(null);
        setStartedAtEpochMs(null);
        setUpdatedAtEpochMs(null);
    }

    private static String first(Map<String, String> h, String... keys) {
        if (h == null) {
            return null;
        }
        for (String k : keys) {
            String v = h.get(k);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    public static final class $Concrete extends CallSessionSbb {

        private final Map<String, Object> local = new ConcurrentHashMap<>();

        public $Concrete() {
            super();
        }

        public $Concrete(CallSessionService sessions, String fsNodeId) {
            super(sessions, fsNodeId);
        }

        @Override public String getFsUuid() { return str("fsUuid"); }
        @Override public void setFsUuid(String v) { put("fsUuid", v); write("setFsUuid", String.class, v); }

        @Override public String getSipCallId() { return str("sipCallId"); }
        @Override public void setSipCallId(String v) { put("sipCallId", v); write("setSipCallId", String.class, v); }

        @Override public String getMsisdn() { return str("msisdn"); }
        @Override public void setMsisdn(String v) { put("msisdn", v); write("setMsisdn", String.class, v); }

        @Override public String getFromUri() { return str("fromUri"); }
        @Override public void setFromUri(String v) { put("fromUri", v); write("setFromUri", String.class, v); }

        @Override public String getToUri() { return str("toUri"); }
        @Override public void setToUri(String v) { put("toUri", v); write("setToUri", String.class, v); }

        @Override public String getFsNodeId() { return str("fsNodeId"); }
        @Override public void setFsNodeId(String v) { put("fsNodeId", v); write("setFsNodeId", String.class, v); }

        @Override public String getElisaNodeId() { return str("elisaNodeId"); }
        @Override public void setElisaNodeId(String v) { put("elisaNodeId", v); write("setElisaNodeId", String.class, v); }

        @Override public String getDialogState() { return str("dialogState"); }
        @Override public void setDialogState(String v) { put("dialogState", v); write("setDialogState", String.class, v); }

        @Override public String getDirection() { return str("direction"); }
        @Override public void setDirection(String v) { put("direction", v); write("setDirection", String.class, v); }

        @Override public Long getStartedAtEpochMs() { return lng("startedAtEpochMs"); }
        @Override public void setStartedAtEpochMs(Long v) { put("startedAtEpochMs", v); write("setStartedAtEpochMs", Long.class, v); }

        @Override public Long getUpdatedAtEpochMs() { return lng("updatedAtEpochMs"); }
        @Override public void setUpdatedAtEpochMs(Long v) { put("updatedAtEpochMs", v); write("setUpdatedAtEpochMs", Long.class, v); }

        private String str(String key) {
            Object v = local.get(key);
            return v instanceof String s ? s : null;
        }

        private Long lng(String key) {
            Object v = local.get(key);
            return v instanceof Long l ? l : null;
        }

        private void put(String key, Object value) {
            if (value == null) {
                local.remove(key);
            } else {
                local.put(key, value);
            }
        }

        private void write(String setter, Class<?> type, Object value) {
            try {
                cmpWrite(method(setter, type), value);
            } catch (IllegalStateException unbound) {
                // local map only when container store unbound
            }
        }

        private static Method method(String name, Class<?>... params) {
            try {
                return CallSessionSbb.class.getMethod(name, params);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
