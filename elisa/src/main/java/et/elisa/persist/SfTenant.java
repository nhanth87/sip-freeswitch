package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sf_tenant")
public class SfTenant extends PanacheEntityBase {
    @Id
    public String id;
    @Column(nullable = false)
    public String name;
    @Column(nullable = false)
    public String kind = "LAB";
    @Column(nullable = false)
    public boolean enabled = true;
    @Column(name = "http_api_key")
    public String httpApiKey;
    @Column(name = "http_api_key_fp")
    public String httpApiKeyFp;
    @Column(name = "source_addr_alias")
    public String sourceAddrAlias;
    @Column(name = "reseller_id")
    public String resellerId;
    @Column(name = "trunk_transport")
    public String trunkTransport;
    @Column(name = "trunk_config", nullable = false)
    public String trunkConfig = "{}";
    @Column(name = "created_at")
    public Instant createdAt;
    @Column(name = "updated_at")
    public Instant updatedAt;
}
