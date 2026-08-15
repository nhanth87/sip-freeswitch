package et.epc.profile;

import com.microjainslee.api.ProfileAbstractCmp;
import com.microjainslee.api.ProfileAccessorInvoker;
import java.lang.reflect.Method;

/** Provisioned APN / PDN. Not a live bearer. */
public final class ApnProfile extends ProfileAbstractCmp {

    public static final String TABLE = "ApnProfile";

    public String getApn() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("apn"));
    }

    public void setApn(String apn) {
        ProfileAccessorInvoker.setValue(this, findSetter("apn", String.class), apn);
    }

    public String getPdnType() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("pdnType"));
    }

    public void setPdnType(String pdnType) {
        ProfileAccessorInvoker.setValue(this, findSetter("pdnType", String.class), pdnType);
    }

    public String getIpv4Pool() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("ipv4Pool"));
    }

    public void setIpv4Pool(String ipv4Pool) {
        ProfileAccessorInvoker.setValue(this, findSetter("ipv4Pool", String.class), ipv4Pool);
    }

    private static Method findGetter(String field) {
        String n = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return ApnProfile.class.getMethod(n);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Method findSetter(String field, Class<?> type) {
        String n = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return ApnProfile.class.getMethod(n, type);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
