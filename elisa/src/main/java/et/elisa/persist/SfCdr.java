package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/** RestComm-shaped CDR + Elisa tenant/reseller/trunk scoping (grill T6b). */
@Entity
@Table(name = "sf_cdr")
public class SfCdr extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "call_uuid")
    public String callUuid;
    @Column(name = "parent_call_uuid")
    public String parentCallUuid;
    @Column(name = "tenant_id")
    public String tenantId;
    @Column(name = "trunk_tenant_id")
    public String trunkTenantId;
    @Column(name = "reseller_id")
    public String resellerId;
    @Column(name = "source_addr")
    public String sourceAddr;

    @Column(name = "date_created", nullable = false)
    public Instant dateCreated;
    @Column(name = "date_updated", nullable = false)
    public Instant dateUpdated;
    @Column(name = "account_sid")
    public String accountSid;
    @Column(length = 255)
    public String sender;
    @Column(length = 64)
    public String recipient;
    @Column(name = "phone_number_sid")
    public String phoneNumberSid;
    @Column(length = 32)
    public String status;
    @Column(name = "start_time")
    public Instant startTime;
    @Column(name = "end_time")
    public Instant endTime;
    public Integer duration;
    @Column(length = 16)
    public String price;
    @Column(name = "price_unit", length = 8)
    public String priceUnit;
    @Column(length = 20)
    public String direction;
    @Column(name = "answered_by")
    public String answeredBy;
    @Column(name = "api_version")
    public String apiVersion;
    @Column(name = "forwarded_from")
    public String forwardedFrom;
    @Column(name = "caller_name")
    public String callerName;
    @Column(columnDefinition = "text")
    public String uri;
    @Column(name = "call_path")
    public String callPath;
    @Column(name = "ring_duration")
    public Integer ringDuration;
    @Column(name = "instance_id")
    public String instanceId;
    @Column(name = "conference_sid")
    public String conferenceSid;
    public Boolean muted;
    @Column(name = "start_conference_on_enter")
    public Boolean startConferenceOnEnter;
    @Column(name = "end_conference_on_exit")
    public Boolean endConferenceOnExit;
    @Column(name = "on_hold")
    public Boolean onHold;
    @Column(name = "ms_id")
    public String msId;
    @Column(name = "call_type")
    public String callType;
    @Column(name = "hangup_cause")
    public String hangupCause;

    @Column(name = "sip_call_id")
    public String sipCallId;
    @Column(length = 32)
    public String msisdn;
    /** P-Charging-Vector icid-value (lab IMS charging correlate). */
    @Column(length = 64)
    public String icid;

    @Column(columnDefinition = "text")
    public String payload;
    @Column(name = "created_at")
    public Instant createdAt;
}
