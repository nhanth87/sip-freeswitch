package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sf_ivr_menu")
public class SfIvrMenu extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;
    @Column(nullable = false)
    public String name;
    @Column(name = "greeting_prompt")
    public String greetingPrompt;
    @Column(name = "timeout_ms", nullable = false)
    public int timeoutMs = 5000;
    @Column(name = "max_failures", nullable = false)
    public int maxFailures = 3;
    @Column(name = "xml_override", columnDefinition = "text")
    public String xmlOverride;
    @Column(nullable = false)
    public boolean enabled = true;
    @Column(name = "updated_at")
    public Instant updatedAt;
}
