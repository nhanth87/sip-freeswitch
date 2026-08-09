package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/** Dual-write PG mirror of live SIP↔FS call correlation (grill P2). */
@Entity
@Table(name = "sf_call_session")
public class SfCallSession extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "fs_uuid", nullable = false, unique = true, length = 128)
    public String fsUuid;
    @Column(name = "sip_call_id")
    public String sipCallId;
    @Column(name = "profile_id")
    public Long profileId;
    @Column(length = 32)
    public String msisdn;
    @Column(name = "tenant_id", length = 80)
    public String tenantId;
    @Column(name = "elisa_node_id", length = 80)
    public String elisaNodeId;
    @Column(name = "fs_node_id", length = 80)
    public String fsNodeId;
    @Column(length = 20)
    public String direction;
    @Column(nullable = false, length = 32)
    public String state = "CREATED";
    @Column(name = "started_at", nullable = false)
    public Instant startedAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();
    @Column(name = "ended_at")
    public Instant endedAt;
}
