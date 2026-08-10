package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Durable pending OCS debit row (grill G3). */
@Entity
@Table(name = "sf_pending_charge")
public class PendingChargeEntity extends PanacheEntityBase {

    @Id
    @Column(name = "charge_key", nullable = false, length = 160)
    public String chargeKey;

    @Column(length = 128)
    public String icid;

    @Column(name = "sip_call_id", length = 256)
    public String sipCallId;

    @Column(length = 128)
    public String account;

    @Column(length = 256)
    public String destination;

    @Column(name = "usage_str", length = 32)
    public String usageStr;

    @Column(nullable = false, length = 16)
    public String state = "PENDING";

    @Column(name = "enqueued_at", nullable = false)
    public Instant enqueuedAt = Instant.now();

    @Column(name = "settled_at")
    public Instant settledAt;
}
