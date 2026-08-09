package et.elisa.profile;

import com.microjainslee.api.ProfileAbstractCmp;
import com.microjainslee.api.ProfileAccessorInvoker;
import java.lang.reflect.Method;

/**
 * SLEE ProfileSpecification — live call correlate (JSR-240 §10).
 * PK = FreeSWITCH Unique-ID ({@code fsUuid}). Indexes: sipCallId, msisdn.
 */
public final class CallSessionProfile extends ProfileAbstractCmp {

    public static final String TABLE = "CallSession";

    public String getFsUuid() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("fsUuid"));
    }

    public void setFsUuid(String fsUuid) {
        ProfileAccessorInvoker.setValue(this, findSetter("fsUuid", String.class), fsUuid);
    }

    public String getSipCallId() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("sipCallId"));
    }

    public void setSipCallId(String sipCallId) {
        ProfileAccessorInvoker.setValue(this, findSetter("sipCallId", String.class), sipCallId);
    }

    public String getMsisdn() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("msisdn"));
    }

    public void setMsisdn(String msisdn) {
        ProfileAccessorInvoker.setValue(this, findSetter("msisdn", String.class), msisdn);
    }

    public String getTenantId() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("tenantId"));
    }

    public void setTenantId(String tenantId) {
        ProfileAccessorInvoker.setValue(this, findSetter("tenantId", String.class), tenantId);
    }

    public String getElisaNodeId() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("elisaNodeId"));
    }

    public void setElisaNodeId(String elisaNodeId) {
        ProfileAccessorInvoker.setValue(this, findSetter("elisaNodeId", String.class), elisaNodeId);
    }

    public String getFsNodeId() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("fsNodeId"));
    }

    public void setFsNodeId(String fsNodeId) {
        ProfileAccessorInvoker.setValue(this, findSetter("fsNodeId", String.class), fsNodeId);
    }

    public String getDirection() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("direction"));
    }

    public void setDirection(String direction) {
        ProfileAccessorInvoker.setValue(this, findSetter("direction", String.class), direction);
    }

    public String getState() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("state"));
    }

    public void setState(String state) {
        ProfileAccessorInvoker.setValue(this, findSetter("state", String.class), state);
    }

    public Long getStartedAtEpochMs() {
        return (Long) ProfileAccessorInvoker.getValue(this, findGetter("startedAtEpochMs"));
    }

    public void setStartedAtEpochMs(Long startedAtEpochMs) {
        ProfileAccessorInvoker.setValue(this, findSetter("startedAtEpochMs", Long.class), startedAtEpochMs);
    }

    public Long getUpdatedAtEpochMs() {
        return (Long) ProfileAccessorInvoker.getValue(this, findGetter("updatedAtEpochMs"));
    }

    public void setUpdatedAtEpochMs(Long updatedAtEpochMs) {
        ProfileAccessorInvoker.setValue(this, findSetter("updatedAtEpochMs", Long.class), updatedAtEpochMs);
    }

    public String getCheckpointJson() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("checkpointJson"));
    }

    public void setCheckpointJson(String checkpointJson) {
        ProfileAccessorInvoker.setValue(this, findSetter("checkpointJson", String.class), checkpointJson);
    }

    private static Method findGetter(String field) {
        String name = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return CallSessionProfile.class.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no getter for " + field, e);
        }
    }

    private static Method findSetter(String field, Class<?> type) {
        String name = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return CallSessionProfile.class.getDeclaredMethod(name, type);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no setter for " + field, e);
        }
    }
}
