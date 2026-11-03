package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import et.epc.api.Imsi;
import et.epc.crypto.Milenage;
import et.epc.crypto.UmtsVector;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * AMF is 16 bits but is stored as one AES block. Returning the block put 16 octets of
 * "AMF" into AUTN, pushing MAC-A out of RAND‖AUTN (every Postgres-backed vector failed
 * at the USIM — seen as SIPp "MAC != expectedMAC").
 */
class JdbcSecretStoreAmfTest {

    static final HexFormat HEX = HexFormat.of();
    static final Imsi IMSI = new Imsi("001010000000009");
    static final byte[] KI = HEX.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc");
    static final byte[] OPC = HEX.parseHex("cd63cb71954a9f4e48a5994e37a02baf");

    @Test
    void amfComesBackAsTwoOctetsAndTheVectorKeepsItsMac() {
        JdbcSecretStore store = new JdbcSecretStore(fakeDb(), "000102030405060708090a0b0c0d0e0f");
        store.put(IMSI, KI, OPC, HEX.parseHex("8000"));

        byte[] amf = store.amf(IMSI);
        assertArrayEquals(HEX.parseHex("8000"), amf);

        UmtsVector v = UmtsVector.generate(store.ki(IMSI), store.opc(IMSI), amf, 33L);
        assertEquals(16, v.autn().length, "AUTN = SQN⊕AK(6) ‖ AMF(2) ‖ MAC-A(8)");
        byte[] mac = Milenage.compute(KI, OPC, v.rand(), java.util.Arrays.copyOfRange(sqn6(33L), 0, 6), amf).macA();
        assertArrayEquals(mac, java.util.Arrays.copyOfRange(v.autn(), 8, 16));
    }

    @Test
    void sixteenOctetAmfIsRejectedByMilenage() {
        assertThrows(IllegalArgumentException.class,
                () -> Milenage.compute(KI, OPC, new byte[16], new byte[6], new byte[16]));
    }

    private static byte[] sqn6(long sqn) {
        byte[] b = new byte[6];
        for (int i = 5; i >= 0; i--) {
            b[i] = (byte) sqn;
            sqn >>>= 8;
        }
        return b;
    }

    /** epc_secret in a map: INSERT … VALUES (imsi, ki, opc, amf) / SELECT <column> WHERE imsi = ?. */
    private static DataSource fakeDb() {
        Map<String, String> row = new HashMap<>();
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (ds, m, a) -> {
                    if (!m.getName().equals("getConnection")) {
                        return null;
                    }
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                            (c, cm, ca) -> {
                                if (!cm.getName().equals("prepareStatement")) {
                                    return null;
                                }
                                String sql = (String) ca[0];
                                Map<Integer, String> params = new HashMap<>();
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                        new Class<?>[] {PreparedStatement.class}, (p, pm, pa) -> switch (pm.getName()) {
                                            case "setString" -> {
                                                params.put((Integer) pa[0], (String) pa[1]);
                                                yield null;
                                            }
                                            case "executeUpdate" -> {
                                                row.put("ki_enc", params.get(2));
                                                row.put("opc_enc", params.get(3));
                                                row.put("amf_enc", params.get(4));
                                                yield 1;
                                            }
                                            case "executeQuery" -> {
                                                String column = sql.substring(7, sql.indexOf(' ', 7));
                                                boolean[] next = {true};
                                                yield Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                                                        new Class<?>[] {ResultSet.class}, (r, rm, ra) -> switch (rm.getName()) {
                                                            case "next" -> {
                                                                boolean n = next[0];
                                                                next[0] = false;
                                                                yield n;
                                                            }
                                                            case "getString" -> row.get(column);
                                                            default -> null;
                                                        });
                                            }
                                            default -> null;
                                        });
                            });
                });
    }
}
