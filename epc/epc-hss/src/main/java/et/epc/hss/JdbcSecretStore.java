package et.epc.hss;

import et.epc.api.Imsi;
import et.epc.crypto.AesEcb;
import et.epc.crypto.Bytes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Postgres HSS secret store. Ki/OPc/AMF are AES-wrapped at rest with a 16-byte
 * key-encryption-key (KEK) supplied at boot — never stored in cleartext. The
 * subscriber row references material only by {@code key_ref} = {@code IMSI}.
 * Production supersedes the KEK with an HSM; the interface stays {@link SecretStore}.
 */
public final class JdbcSecretStore implements SecretStore {

    private final DataSource ds;
    private final byte[] kek;

    public JdbcSecretStore(DataSource ds, byte[] kek16) {
        this.ds = Objects.requireNonNull(ds);
        if (kek16 == null || kek16.length != 16) {
            throw new IllegalArgumentException("KEK must be 16 bytes");
        }
        this.kek = kek16.clone();
    }

    /** Convenience constructor: parse a 32-hex-char KEK. */
    public JdbcSecretStore(DataSource ds, String kekHex) {
        this(ds, HexFormat.of().parseHex(kekHex.trim().replace(" ", "")));
    }

    public void put(Imsi imsi, byte[] ki, byte[] opc, byte[] amf) {
        Objects.requireNonNull(imsi);
        require16(ki, "ki");
        require16(opc, "opc");
        if (amf != null && amf.length == 2) {
            amf = java.util.Arrays.copyOf(amf, 16); // one AES block, zero-padded
        }
        require16(amf, "amf");
        String sql = """
                INSERT INTO epc_secret (imsi, ki_enc, opc_enc, amf_enc, kek_version)
                VALUES (?,?,?,?,1)
                ON CONFLICT (imsi) DO UPDATE SET
                  ki_enc = EXCLUDED.ki_enc,
                  opc_enc = EXCLUDED.opc_enc,
                  amf_enc = EXCLUDED.amf_enc,
                  updated_at = now()
                """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, imsi.digits());
            ps.setString(2, wrap(ki));
            ps.setString(3, wrap(opc));
            ps.setString(4, wrap(amf));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("hss secret put", e);
        }
    }

    @Override
    public byte[] ki(Imsi imsi) {
        return unwrap(imsi, "ki_enc");
    }

    @Override
    public byte[] opc(Imsi imsi) {
        return unwrap(imsi, "opc_enc");
    }

    /**
     * AMF is 16 bits (TS 33.102 §6.3.2); it is stored as one AES block (zero-padded) like
     * Ki/OPc. Returning the whole block put 16 octets of "AMF" into every AUTN — the MAC-A
     * fell off the end of RAND‖AUTN and no USIM could verify a Postgres-backed vector.
     */
    @Override
    public byte[] amf(Imsi imsi) {
        return java.util.Arrays.copyOf(unwrap(imsi, "amf_enc"), 2);
    }

    private byte[] unwrap(Imsi imsi, String column) {
        String sql = "SELECT " + column + " FROM epc_secret WHERE imsi = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, imsi.digits());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("no secret for IMSI " + imsi.digits());
                }
                return AesEcb.decrypt(kek, Bytes.parseHex(rs.getString(1)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("hss secret read", e);
        }
    }

    private String wrap(byte[] block16) {
        return Bytes.toHex(AesEcb.encrypt(kek, block16));
    }

    private static void require16(byte[] v, String name) {
        if (v == null || v.length != 16) {
            throw new IllegalArgumentException(name + " must be 16 bytes");
        }
    }
}