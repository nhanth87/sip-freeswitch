package et.elisa.media;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Locale;

/**
 * Media path policy for firewall traversal ({@code rtp_redirect}).
 *
 * <p>Elisa is SIP-only — it does not relay RTP. {@code rtp_redirect} means
 * media must use a path that can cross the firewall: ICE + TURN {@code typ relay}
 * (and FreeSWITCH must not {@code bypass_media}). Peer-to-peer host/srflx-only
 * paths are rejected when redirect is on.
 */
@ApplicationScoped
public class MediaIceTurnPolicy {

    @ConfigProperty(name = "sf.media.ice-required", defaultValue = "true")
    boolean iceRequired;

    /**
     * Explicit TURN requirement. When {@link #rtpRedirect} is true, TURN is
     * forced even if this flag is false.
     */
    @ConfigProperty(name = "sf.media.require-turn", defaultValue = "true")
    boolean requireTurn;

    /**
     * Media must traverse firewall via TURN relay (rtp_redirect). Default on.
     */
    @ConfigProperty(name = "sf.media.rtp-redirect", defaultValue = "true")
    boolean rtpRedirect;

    @ConfigProperty(name = "sf.media.turn.uri", defaultValue = "turn:127.0.0.1:3478")
    String turnUri;

    @ConfigProperty(name = "sf.media.turn.username", defaultValue = "elisa")
    String turnUsername;

    @ConfigProperty(name = "sf.media.turn.credential", defaultValue = "elisaturn")
    String turnCredential;

    @ConfigProperty(name = "sf.media.stun.uri", defaultValue = "stun:stun.l.google.com:3478")
    String stunUri;

    public boolean iceRequired() {
        return iceRequired;
    }

    public boolean requireTurn() {
        return requireTurn || rtpRedirect;
    }

    public boolean rtpRedirect() {
        return rtpRedirect;
    }

    public String turnUri() {
        return turnUri;
    }

    public String turnUsername() {
        return turnUsername;
    }

    public String turnCredential() {
        return turnCredential;
    }

    public String stunUri() {
        return stunUri;
    }

    /**
     * @return null if OK, else SIP reason phrase for 488
     */
    public String rejectReason(String sdpBody) {
        boolean needIce = iceRequired || rtpRedirect;
        boolean needRelay = requireTurn();
        if (!needIce && !needRelay) {
            return null;
        }
        String sdp = sdpBody == null ? "" : sdpBody.toLowerCase(Locale.ROOT);
        boolean hasIce = sdp.contains("a=ice-ufrag") || sdp.contains("a=candidate:");
        if (needIce && !hasIce) {
            return "ICE required";
        }
        boolean hasRelay = sdp.contains("typ relay");
        if (needRelay && !hasRelay) {
            return rtpRedirect ? "RTP redirect (TURN relay) required" : "TURN relay candidate required";
        }
        return null;
    }
}
