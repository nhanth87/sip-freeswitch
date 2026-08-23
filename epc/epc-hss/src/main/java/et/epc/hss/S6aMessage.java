package et.epc.hss;

import com.microjainslee.ra.diameter.avp.DiameterAvp;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Diameter-lite S6a request/answer carrier.
 *
 * <p>Legacy flat {@code Map<Integer,byte[]>} AVPs are kept for the string/simple
 * seam; the {@code structuredAvps} list carries the 3GPP-grouped AVPs
 * (Authentication-Info, E-UTRAN-Vector, Subscription-Data, AMBR, …) the Open5GS
 * MME/client consumes. {@code ra-diameter} now decodes inbound groups/octets, so
 * the resync (SQN/AUTS) path reads RAND + Re-Synchronization-Info from this list.
 */
public record S6aMessage(S6aCommand command, boolean request, Map<Integer, byte[]> avps,
                         List<DiameterAvp> structuredAvps) {

    public S6aMessage {
        Objects.requireNonNull(command);
        avps = Map.copyOf(avps == null ? Map.of() : avps);
        structuredAvps = structuredAvps == null ? List.of() : List.copyOf(structuredAvps);
    }

    /** Back-compat 3-arg form without structured AVPs. */
    public S6aMessage(S6aCommand command, boolean request, Map<Integer, byte[]> avps) {
        this(command, request, avps, List.of());
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

    /** First structured AVP with the given code, or null. */
    public DiameterAvp findStructured(int code) {
        for (DiameterAvp a : structuredAvps) {
            if (a.code() == code) {
                return a;
            }
        }
        return null;
    }

    /** Octet-string value of the first structured AVP with the given code, or null. */
    public byte[] structuredBytes(int code) {
        DiameterAvp a = findStructured(code);
        return a == null ? null : a.asOctets();
    }

    public static final class Builder {
        private final S6aCommand command;
        private final boolean request;
        private final Map<Integer, byte[]> avps = new LinkedHashMap<>();
        private List<DiameterAvp> structuredAvps = List.of();

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

        public Builder structured(List<DiameterAvp> avps) {
            this.structuredAvps = avps == null ? List.of() : avps;
            return this;
        }

        public S6aMessage build() {
            return new S6aMessage(command, request, avps, structuredAvps);
        }
    }
}
