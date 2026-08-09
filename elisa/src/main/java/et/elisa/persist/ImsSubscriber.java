package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ims_subscriber")
public class ImsSubscriber extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, unique = true)
    public String impi;
    @Column(nullable = false)
    public String impu;
    @Column(nullable = false, length = 64)
    public String ki;
    @Column(nullable = false, length = 64)
    public String opc;
    @Column(nullable = false, length = 8)
    public String amf = "8000";
    @Column(nullable = false)
    public long sqn;
    @Column(name = "ifc_json")
    public String ifcJson;
    @Column(name = "tenant_id", length = 80)
    public String tenantId;
    @Column(length = 32)
    public String msisdn;
    @Column(name = "display_name", length = 160)
    public String displayName;
    @Column(name = "fs_a1_hash", length = 64)
    public String fsA1Hash;
    @Column(nullable = false)
    public boolean enabled = true;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
    @Column(name = "updated_at")
    public Instant updatedAt = Instant.now();

    /** Sofia / xml_curl user id: MSISDN preferred, else SIP user-part of IMPU. */
    public String directoryUserId() {
        if (msisdn != null && !msisdn.isBlank()) {
            return msisdn.trim();
        }
        if (impu == null) {
            return impi;
        }
        String u = impu.replaceFirst("(?i)^sip:", "");
        int at = u.indexOf('@');
        return at > 0 ? u.substring(0, at) : u;
    }
}
