package et.elisa.ims.charging;

/**
 * Diameter Rf ACR emitter (Phase B stub). Real {@code ra-diameter} ACR can replace the logging impl later.
 */
public interface RfAcrEmitter {

    /** Emit ACR STOP (or no-op when disabled). Soft-fail — must not throw. */
    void emitStop(RfAcrRequest request);
}
