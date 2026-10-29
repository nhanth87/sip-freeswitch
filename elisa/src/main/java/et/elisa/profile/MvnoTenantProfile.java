package et.elisa.profile;

import com.microjainslee.api.ProfileAbstractCmp;
import com.microjainslee.api.ProfileAccessorInvoker;
import java.lang.reflect.Method;

/**
 * SLEE ProfileSpecification (JSR-240 §10) — MVNO tenant service config hot store, read on
 * the S-CSCF INVITE path without touching Postgres. PK = tenantId. Postgres
 * {@code sf_tenant} stays the SoT; admin writes upsert it and then this row.
 */
public final class MvnoTenantProfile extends ProfileAbstractCmp {

    public static final String TABLE = "MvnoTenant";

    public String getTenantId() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("tenantId"));
    }

    public void setTenantId(String tenantId) {
        ProfileAccessorInvoker.setValue(this, findSetter("tenantId", String.class), tenantId);
    }

    public String getName() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("name"));
    }

    public void setName(String name) {
        ProfileAccessorInvoker.setValue(this, findSetter("name", String.class), name);
    }

    /** {@code "true"} / {@code "false"} string (C7 whitelist). */
    public String getEnabled() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("enabled"));
    }

    public void setEnabled(String enabled) {
        ProfileAccessorInvoker.setValue(this, findSetter("enabled", String.class), enabled);
    }

    public Long getMaxCps() {
        return (Long) ProfileAccessorInvoker.getValue(this, findGetter("maxCps"));
    }

    public void setMaxCps(Long maxCps) {
        ProfileAccessorInvoker.setValue(this, findSetter("maxCps", Long.class), maxCps);
    }

    public String getMsisdnPrefix() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("msisdnPrefix"));
    }

    public void setMsisdnPrefix(String msisdnPrefix) {
        ProfileAccessorInvoker.setValue(this, findSetter("msisdnPrefix", String.class), msisdnPrefix);
    }

    public String getIscAsUri() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("iscAsUri"));
    }

    public void setIscAsUri(String iscAsUri) {
        ProfileAccessorInvoker.setValue(this, findSetter("iscAsUri", String.class), iscAsUri);
    }

    public Long getUpdatedAtEpochMs() {
        return (Long) ProfileAccessorInvoker.getValue(this, findGetter("updatedAtEpochMs"));
    }

    public void setUpdatedAtEpochMs(Long updatedAtEpochMs) {
        ProfileAccessorInvoker.setValue(this, findSetter("updatedAtEpochMs", Long.class), updatedAtEpochMs);
    }

    public boolean isEnabled() {
        return !"false".equalsIgnoreCase(getEnabled());
    }

    private static Method findGetter(String field) {
        String name = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return MvnoTenantProfile.class.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no getter for " + field, e);
        }
    }

    private static Method findSetter(String field, Class<?> type) {
        String name = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return MvnoTenantProfile.class.getDeclaredMethod(name, type);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no setter for " + field, e);
        }
    }
}
