package et.epc.api.link;

import java.util.Objects;

/**
 * Link-status truth: {@link #live} is peer evidence only.
 * Local LISTEN / RA started must never flip live to true.
 */
public record PeerLink(
        String name,
        boolean localListen,
        boolean live,
        String evidence
) {
    public PeerLink {
        Objects.requireNonNull(name);
        Objects.requireNonNull(evidence);
        if (live && evidence.isBlank()) {
            throw new IllegalArgumentException("live requires peer evidence");
        }
    }

    public static PeerLink down(String name, boolean localListen, String why) {
        return new PeerLink(name, localListen, false, why);
    }

    public static PeerLink up(String name, String evidence) {
        return new PeerLink(name, true, true, evidence);
    }
}
