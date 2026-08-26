package et.elisa.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Optional;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Postgres store for AES-wrapped IMS Ki/OPc ({@code ims_secret}), keyed by IMPI.
 * Plaintext never touches {@code ims_subscriber} after the W2-a boot migration.
 * KEK from {@code sf.ims.secret.kek-hex}; production supersedes with HSM.
 */
@ApplicationScoped
public class ImsSecretStore {

    public record StoredSecret(byte[] ki, byte[] opc) {
    }

    private final DataSource ds;
    private final byte[] kek;

    @Inject
    public ImsSecretStore(
            DataSource ds,
            @ConfigProperty(name = "sf.ims.secret.kek-hex",
                    defaultValue = "0f1e2d3c4b5a69788796a5b4c3d2e1f0") String kekHex) {
        this.ds = ds;
        this.kek = ImsSecretCrypto.kekFromHex(kekHex);
    }

    /** Unit-test ctor — no DB (put/find unsupported). */
    protected ImsSecretStore() {
        this.ds = null;
        this.kek = new byte[16];
    }

    public void put(String impi, byte[] ki16, byte[] opc16) {
        if (ds == null) {
            throw new IllegalStateException("ImsSecretStore not wired (unit ctor)");
        }
        String sql = """
                INSERT INTO ims_secret (impi, ki_enc, opc_enc, kek_version)
                VALUES (?,?,?,1)
                ON CONFLICT (impi) DO UPDATE SET
                  ki_enc = EXCLUDED.ki_enc,
                  opc_enc = EXCLUDED.opc_enc,
                  updated_at = now()
                """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, impi);
            ps.setString(2, ImsSecretCrypto.wrap(kek, ki16));
            ps.setString(3, ImsSecretCrypto.wrap(kek, opc16));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("ims secret put failed impi=" + impi, e);
        }
    }

    /** Fail-closed: empty when no wrapped material exists — caller must 5001. */
    public Optional<StoredSecret> find(String impi) {
        if (ds == null || impi == null || impi.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT ki_enc, opc_enc FROM ims_secret WHERE impi = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, impi);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredSecret(
                        ImsSecretCrypto.unwrap(kek, rs.getString(1)),
                        ImsSecretCrypto.unwrap(kek, rs.getString(2))));
            }
        } catch (SQLException | IllegalArgumentException e) {
            throw new IllegalStateException("ims secret read failed impi=" + impi, e);
        }
    }

    /** Hex convenience for admin/provisioning paths. */
    public void putHex(String impi, String kiHex, String opcHex) {
        put(impi,
                ImsSecretCrypto.parseKeyHex(kiHex, "ki"),
                ImsSecretCrypto.parseKeyHex(opcHex, "opc"));
    }

    static String toHex(byte[] v) {
        return HexFormat.of().formatHex(v);
    }
}
