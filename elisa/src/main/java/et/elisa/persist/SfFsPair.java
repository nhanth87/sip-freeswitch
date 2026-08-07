package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.io.Serializable;

@Entity
@Table(name = "sf_fs_pair")
@IdClass(SfFsPair.Pk.class)
public class SfFsPair extends PanacheEntityBase {

    @Id
    @Column(name = "elisa_node_id")
    public String elisaNodeId;

    @Id
    @Column(name = "fs_node_id")
    public String fsNodeId;

    @Column(nullable = false)
    public int priority = 100;

    public static final class Pk implements Serializable {
        public String elisaNodeId;
        public String fsNodeId;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Pk pk)) {
                return false;
            }
            return java.util.Objects.equals(elisaNodeId, pk.elisaNodeId)
                    && java.util.Objects.equals(fsNodeId, pk.fsNodeId);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(elisaNodeId, fsNodeId);
        }
    }
}
