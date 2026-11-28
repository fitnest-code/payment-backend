package az.fitnest.payment.model.entity;

import az.fitnest.payment.model.enums.BolOdeApplicationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Manual Kapital "Böl ödə" application (see V15 migration for the why). */
@Entity
@Table(name = "bol_ode_applications")
@Getter
@Setter
@NoArgsConstructor
public class BolOdeApplication extends BaseEntity {

    @Column(name = "display_id", nullable = false, unique = true, length = 32)
    private String displayId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "payment_reference", length = 128)
    private String paymentReference;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "fin_code", nullable = false, length = 16)
    private String finCode;

    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    @Column(name = "extra_phone1", length = 32)
    private String extraPhone1;

    @Column(name = "extra_phone2", length = 32)
    private String extraPhone2;

    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    @Column(name = "birbank_consent_accepted", nullable = false)
    private boolean birbankConsentAccepted;

    @Column(name = "mygov_consent_accepted", nullable = false)
    private boolean mygovConsentAccepted;

    @Column(name = "package_name")
    private String packageName;

    @Column(name = "duration_months")
    private Integer durationMonths;

    @Column(name = "final_amount", precision = 12, scale = 2)
    private BigDecimal finalAmount;

    @Column(name = "option_id")
    private Long optionId;

    @Column(name = "package_id")
    private Long packageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private BolOdeApplicationStatus status;

    @Column(name = "decline_reason", columnDefinition = "TEXT")
    private String declineReason;

    @Column(name = "decline_reason_visible_to_customer")
    private Boolean declineReasonVisibleToCustomer = Boolean.TRUE;

    @Column(name = "assigned_admin_user_id")
    private Long assignedAdminUserId;

    @Column(name = "internal_note", columnDefinition = "TEXT")
    private String internalNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
