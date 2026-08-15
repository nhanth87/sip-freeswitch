package et.epc.hss;

import et.epc.api.Imsi;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Lab/test secret store. Production must replace with HSM dual-control. */
public final class HeapSecretStore implements SecretStore {

    private record Material(byte[] ki, byte[] opc, byte[] amf) {}

    private final ConcurrentHashMap<String, Material> secrets = new ConcurrentHashMap<>();

    public void put(Imsi imsi, byte[] ki, byte[] opc, byte[] amf) {
        Objects.requireNonNull(imsi);
        secrets.put(imsi.digits(), new Material(ki.clone(), opc.clone(), amf.clone()));
    }

    @Override
    public byte[] ki(Imsi imsi) {
        return material(imsi).ki.clone();
    }

    @Override
    public byte[] opc(Imsi imsi) {
        return material(imsi).opc.clone();
    }

    @Override
    public byte[] amf(Imsi imsi) {
        return material(imsi).amf.clone();
    }

    private Material material(Imsi imsi) {
        Material m = secrets.get(imsi.digits());
        if (m == null) {
            throw new IllegalArgumentException("no secret for IMSI");
        }
        return m;
    }
}
