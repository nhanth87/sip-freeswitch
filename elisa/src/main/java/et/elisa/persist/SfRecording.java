package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "sf_recording")
public class SfRecording extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "call_uuid")
    public String callUuid;
    @Column(nullable = false)
    public String path;
    @Column(name = "duration_seconds")
    public Integer durationSeconds;
}
