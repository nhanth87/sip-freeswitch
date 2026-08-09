package et.elisa.ims.support;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import et.elisa.ims.config.ImsRoleFlags;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ImsTestSupport {

    private ImsTestSupport() {}

    public static ImsRoleFlags roles(boolean p, boolean i, boolean s, boolean hss) {
        try {
            ImsRoleFlags flags = new ImsRoleFlags();
            set(flags, "enabled", true);
            set(flags, "pcscf", p);
            set(flags, "icscf", i);
            set(flags, "scscf", s);
            set(flags, "hss", hss);
            set(flags, "realm", "ims.lab");
            set(flags, "pcscfUri", "sip:pcscf.ims.lab:5060");
            set(flags, "icscfUri", "sip:icscf.ims.lab:5060");
            set(flags, "scscfUri", "sip:scscf.ims.lab:5060");
            set(flags, "iscAsUri", "sip:as@127.0.0.1:5062");
            return flags;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void injectRa(Object sbb, RaCommandPort port) {
        try {
            Field f = findField(sbb.getClass(), "sipRa");
            f.setAccessible(true);
            f.set(sbb, port);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    public static void set(Object target, String field, Object value) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    public static final class CapturingRa implements RaCommandPort {
        public final List<OutboundCommand> commands = new CopyOnWriteArrayList<>();

        @Override
        public void sendCommand(OutboundCommand command) {
            commands.add(command);
        }

        public <T> List<T> ofType(Class<T> type) {
            List<T> out = new ArrayList<>();
            for (OutboundCommand c : commands) {
                if (type.isInstance(c)) {
                    out.add(type.cast(c));
                }
            }
            return out;
        }
    }
}
