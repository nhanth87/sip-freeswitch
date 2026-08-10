package et.elisa.cluster;

import com.microjainslee.ra.sipservlet.collab.DialogRegistry;
import java.io.Serializable;

/**
 * Portable SIP dialog HA fields dual-written with {@link DialogCheckpoint}.
 * Maps 1:1 to {@link DialogRegistry.PortableDialogMeta} wire essentials
 * (peers + tags + contacts) — not a second SoT; fills RA DialogRegistry on recover.
 */
public record SipDialogHa(
        String remotePeer,
        String transport,
        String remoteTransport,
        long cseq,
        String fromTag,
        String toTag,
        String contactUri,
        String remoteContactUri,
        String method,
        String farFromUri,
        String farFromTag
) implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final SipDialogHa EMPTY =
            new SipDialogHa(null, null, null, 0L, null, null, null, null, null, null, null);

    public boolean isEmpty() {
        return (remotePeer == null || remotePeer.isBlank())
                && (fromTag == null || fromTag.isBlank())
                && (contactUri == null || contactUri.isBlank())
                && (remoteContactUri == null || remoteContactUri.isBlank());
    }

    public static SipDialogHa fromPortable(DialogRegistry.PortableDialogMeta meta) {
        if (meta == null) {
            return EMPTY;
        }
        String peer = meta.peerHost() != null && meta.peerPort() > 0
                ? meta.peerHost() + ":" + meta.peerPort() : null;
        String remote = meta.remotePeerHost() != null && meta.remotePeerPort() > 0
                ? meta.remotePeerHost() + ":" + meta.remotePeerPort() : null;
        // peer stored on DialogCheckpoint.peer; remote here
        return new SipDialogHa(
                remote,
                meta.transport(),
                meta.remoteTransport(),
                meta.cseq(),
                meta.fromTag(),
                meta.toTag(),
                meta.contactUri(),
                meta.remoteContactUri(),
                meta.method(),
                meta.farFromUri(),
                meta.farFromTag());
    }

    /**
     * Build RA portable meta from checkpoint peer + this HA block + dialog URIs.
     */
    public DialogRegistry.PortableDialogMeta toPortable(
            String callId, String peer, String fromUri, String toUri) {
        HostPort ua = HostPort.parse(peer);
        HostPort remote = HostPort.parse(remotePeer);
        return new DialogRegistry.PortableDialogMeta(
                callId,
                ua.host,
                ua.port,
                transport,
                remote.host,
                remote.port,
                remoteTransport != null ? remoteTransport : transport,
                cseq,
                fromUri,
                fromTag,
                toUri,
                toTag,
                contactUri,
                remoteContactUri,
                method != null ? method : "INVITE",
                farFromUri,
                farFromTag);
    }

    private record HostPort(String host, int port) {
        static HostPort parse(String s) {
            if (s == null || s.isBlank()) {
                return new HostPort(null, 0);
            }
            int colon = s.lastIndexOf(':');
            if (colon <= 0 || colon >= s.length() - 1) {
                return new HostPort(s.trim(), 5060);
            }
            try {
                return new HostPort(s.substring(0, colon).trim(),
                        Integer.parseInt(s.substring(colon + 1).trim()));
            } catch (NumberFormatException e) {
                return new HostPort(s.trim(), 5060);
            }
        }
    }
}
