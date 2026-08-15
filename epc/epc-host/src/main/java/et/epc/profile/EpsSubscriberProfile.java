package et.epc.profile;

import com.microjainslee.api.ProfileAbstractCmp;
import com.microjainslee.api.ProfileAccessorInvoker;
import java.lang.reflect.Method;

/** Provisioned EPS subscriber identity. No Ki/OPc/SQN. */
public final class EpsSubscriberProfile extends ProfileAbstractCmp {

    public static final String TABLE = "EpsSubscriber";

    public String getImsi() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("imsi"));
    }

    public void setImsi(String imsi) {
        ProfileAccessorInvoker.setValue(this, findSetter("imsi", String.class), imsi);
    }

    public String getMsisdn() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("msisdn"));
    }

    public void setMsisdn(String msisdn) {
        ProfileAccessorInvoker.setValue(this, findSetter("msisdn", String.class), msisdn);
    }

    public String getDefaultApn() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("defaultApn"));
    }

    public void setDefaultApn(String defaultApn) {
        ProfileAccessorInvoker.setValue(this, findSetter("defaultApn", String.class), defaultApn);
    }

    public String getEnabled() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("enabled"));
    }

    public void setEnabled(String enabled) {
        ProfileAccessorInvoker.setValue(this, findSetter("enabled", String.class), enabled);
    }

    private static Method findGetter(String field) {
        String n = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return EpsSubscriberProfile.class.getMethod(n);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Method findSetter(String field, Class<?> type) {
        String n = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return EpsSubscriberProfile.class.getMethod(n, type);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
