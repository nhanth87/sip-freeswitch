package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "sf_acl_entry")
public class SfAclEntry extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "list_name", nullable = false)
    public String listName;
    @Column(nullable = false)
    public String cidr;
    @Column(nullable = false)
    public String action;
    @Column(nullable = false)
    public boolean enabled = true;
}
