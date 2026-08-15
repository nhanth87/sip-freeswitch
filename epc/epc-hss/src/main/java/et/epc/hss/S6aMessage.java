package et.epc.hss;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Diameter-lite AVP map until ra-diameter SCTP S6a is bound at the host. */
public record S6aMessage(S6aCommand command, boolean request, Map<Integer, byte[]> avps) {

    public S6aMessage {
        Objects.requireNonNull(command);
        avps = Map.copyOf(avps == null ? Map.of() : avps);
    }

    public static Builder builder(S6aCommand command, boolean request) {
        return new Builder(command, request);
    }

    public byte[] avp(int code) {
        return avps.get(code);
    }

    public String avpUtf8(int code) {
        byte[] v = avps.get(code);
        return v == null ? null : new String(v, StandardCharsets.UTF_8);
    }

    public static final class Builder {
        private final S6aCommand command;
        private final boolean request;
        private final Map<Integer, byte[]> avps = new LinkedHashMap<>();

        Builder(S6aCommand command, boolean request) {
            this.command = command;
            this.request = request;
        }

        public Builder put(int code, byte[] value) {
            avps.put(code, value);
            return this;
        }

        public Builder putUtf8(int code, String value) {
            avps.put(code, value.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        public S6aMessage build() {
            return new S6aMessage(command, request, avps);
        }
    }
}
