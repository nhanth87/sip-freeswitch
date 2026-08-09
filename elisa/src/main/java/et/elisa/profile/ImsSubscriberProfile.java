package et.elisa.profile;

import com.microjainslee.api.ProfileAbstractCmp;
import com.microjainslee.api.ProfileAccessorInvoker;
import java.lang.reflect.Method;

/**
 * SLEE ProfileSpecification — IMS identity hot mirror (JSR-240 §10).
 * PK = MSISDN when known, else IMPI. No Ki / OPc / SQN (Postgres HSS remains SoT).
 */
public final class ImsSubscriberProfile extends ProfileAbstractCmp {

    public static final String TABLE = "ImsSubscriber";

    public String getMsisdn() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("msisdn"));
    }

    public void setMsisdn(String msisdn) {
        ProfileAccessorInvoker.setValue(this, findSetter("msisdn", String.class), msisdn);
    }

    public String getImpi() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("impi"));
    }

    public void setImpi(String impi) {
        ProfileAccessorInvoker.setValue(this, findSetter("impi", String.class), impi);
    }

    public String getImpu() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("impu"));
    }

    public void setImpu(String impu) {
        ProfileAccessorInvoker.setValue(this, findSetter("impu", String.class), impu);
    }

    public String getTenantId() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("tenantId"));
    }

    public void setTenantId(String tenantId) {
        ProfileAccessorInvoker.setValue(this, findSetter("tenantId", String.class), tenantId);
    }

    public String getDisplayName() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("displayName"));
    }

    public void setDisplayName(String displayName) {
        ProfileAccessorInvoker.setValue(this, findSetter("displayName", String.class), displayName);
    }

    public String getFsA1Hash() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("fsA1Hash"));
    }

    public void setFsA1Hash(String fsA1Hash) {
        ProfileAccessorInvoker.setValue(this, findSetter("fsA1Hash", String.class), fsA1Hash);
    }

    /** {@code "true"} / {@code "false"} string (C7 whitelist). */
    public String getEnabled() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("enabled"));
    }

    public void setEnabled(String enabled) {
        ProfileAccessorInvoker.setValue(this, findSetter("enabled", String.class), enabled);
    }

    public Long getUpdatedAtEpochMs() {
        return (Long) ProfileAccessorInvoker.getValue(this, findGetter("updatedAtEpochMs"));
    }

    public void setUpdatedAtEpochMs(Long updatedAtEpochMs) {
        ProfileAccessorInvoker.setValue(this, findSetter("updatedAtEpochMs", Long.class), updatedAtEpochMs);
    }

    private static Method findGetter(String field) {
        String name = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return ImsSubscriberProfile.class.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no getter for " + field, e);
        }
    }

    private static Method findSetter(String field, Class<?> type) {
        String name = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return ImsSubscriberProfile.class.getDeclaredMethod(name, type);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no setter for " + field, e);
        }
    }
}
