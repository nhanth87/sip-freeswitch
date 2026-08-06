package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "sf_conference_profile")
public class SfConferenceProfile extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "tenant_id")
    public String tenantId;
    @Column(nullable = false)
    public String name;
    @Column(columnDefinition = "text")
    public String config = "{}";
}
