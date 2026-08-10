package et.elisa.ims.charging;

/**
 * Diameter Rf ACR emitter. Prefer {@code ra-diameter} {@link com.microjainslee.ra.diameter.command.SendDiameterRequest}
 * when peerReady; else structured log fallback ({@link LoggingRfAcrEmitter}).
 */
public interface RfAcrEmitter {

    /** Emit ACR START (INVITE confirmed). Soft-fail — must not throw. */
    default void emitStart(RfAcrRequest request) {
        // optional
    }

    /** Emit ACR STOP (BYE / CDR). Soft-fail — must not throw. */
    void emitStop(RfAcrRequest request);
}
