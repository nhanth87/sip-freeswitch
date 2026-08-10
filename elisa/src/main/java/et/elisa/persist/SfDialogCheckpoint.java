package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Durable PG mirror of {@link et.elisa.cluster.DialogCheckpoint} (CMP recovery). */
@Entity
@Table(name = "sf_dialog_checkpoint")
public class SfDialogCheckpoint extends PanacheEntityBase {

    @Id
    @Column(name = "sip_call_id", nullable = false, length = 255)
    public String sipCallId;

    @Column(length = 255)
    public String peer;

    @Column(name = "from_uri", columnDefinition = "text")
    public String fromUri;

    @Column(name = "to_uri", columnDefinition = "text")
    public String toUri;

    @Column(name = "trunk_uri", columnDefinition = "text")
    public String trunkUri;

    @Column(name = "fs_uuid", length = 128)
    public String fsUuid;

    @Column(length = 32)
    public String msisdn;

    @Column(name = "fs_node_id", length = 80)
    public String fsNodeId;

    @Column(name = "elisa_node_id", length = 80)
    public String elisaNodeId;

    @Column(name = "dialog_state", nullable = false, length = 32)
    public String dialogState = "INVITE";

    @Column(name = "started_at", nullable = false)
    public Instant startedAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    @Column(name = "ended_at")
    public Instant endedAt;

    /** Portable SIP HA (DialogRegistry peers/tags) — lab mid-dialog failover. */
    @Column(name = "remote_peer", length = 255)
    public String remotePeer;

    @Column(length = 16)
    public String transport;

    @Column(name = "remote_transport", length = 16)
    public String remoteTransport;

    @Column
    public long cseq;

    @Column(name = "from_tag", length = 128)
    public String fromTag;

    @Column(name = "to_tag", length = 128)
    public String toTag;

    @Column(name = "contact_uri", columnDefinition = "text")
    public String contactUri;

    @Column(name = "remote_contact_uri", columnDefinition = "text")
    public String remoteContactUri;

    @Column(name = "sip_method", length = 32)
    public String sipMethod;

    @Column(name = "far_from_uri", columnDefinition = "text")
    public String farFromUri;

    @Column(name = "far_from_tag", length = 128)
    public String farFromTag;
}
