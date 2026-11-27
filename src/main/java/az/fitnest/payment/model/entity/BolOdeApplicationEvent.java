package az.fitnest.payment.model.entity;

import az.fitnest.payment.model.enums.BolOdeApplicationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Append-only audit row for a {@link BolOdeApplication}. actorType: SYSTEM | USER | ADMIN. */
@Entity
@Table(name = "bol_ode_application_events")
@Getter
@Setter
@NoArgsConstructor
public class BolOdeApplicationEvent extends BaseEntity {

    public static final String ACTOR_SYSTEM = "SYSTEM";
    public static final String ACTOR_USER = "USER";
    public static final String ACTOR_ADMIN = "ADMIN";

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "actor_type", nullable = false, length = 16)
    private String actorType;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private BolOdeApplicationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 32)
    private BolOdeApplicationStatus toStatus;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "decline_reason", columnDefinition = "TEXT")
    private String declineReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
