package et.epc.hss;

import et.epc.api.Imsi;

/**
 * HSM-backed Ki/OPc. Implementations must never log key material or vectors.
 */
public interface SecretStore {
    byte[] ki(Imsi imsi);

    byte[] opc(Imsi imsi);

    byte[] amf(Imsi imsi);
}
