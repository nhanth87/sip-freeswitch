package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * SIP trunk row (USSDGW-inspired) — ISC AS / MRF / PSTN peer + URI.
 * Shared via Postgres so any S-CSCF node picks the same primary trunk.
 */
@Entity
@Table(name = "sf_sip_trunk")
public class SipTrunkEntity extends PanacheEntityBase {

    public static final String KIND_ISC = "ISC";
    public static final String KIND_MRF = "MRF";
    public static final String KIND_PSTN = "PSTN";

    @Id
    @Column(nullable = false, length = 64)
    public String id;

    @Column(nullable = false, length = 160)
    public String name;

    @Column(name = "peer_host", nullable = false, length = 256)
    public String peerHost;

    @Column(name = "peer_port", nullable = false)
    public int peerPort = 5060;

    @Column(nullable = false, length = 8)
    public String transport = "UDP";

    @Column(name = "sip_uri", nullable = false, length = 512)
    public String sipUri;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "tenant_id", length = 80)
    public String tenantId;

    /** ISC | MRF | PSTN */
    @Column(nullable = false, length = 16)
    public String kind = KIND_ISC;

    @Column(nullable = false)
    public int priority = 100;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();
}
