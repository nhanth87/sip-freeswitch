package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "sf_ivr_entry")
public class SfIvrEntry extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "menu_id", nullable = false)
    public Long menuId;
    @Column(nullable = false, length = 8)
    public String digit;
    @Column(nullable = false, length = 64)
    public String action;
    @Column(length = 255)
    public String destination;
    @Column(nullable = false)
    public int rank = 100;
}
