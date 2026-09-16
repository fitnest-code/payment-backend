package az.fitnest.payment.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "welcome_bonus_identifiers", indexes = {
        @Index(name = "idx_wb_phone_hash", columnList = "phone_hash"),
        @Index(name = "idx_wb_email_hash", columnList = "email_hash"),
        @Index(name = "idx_wb_user_id", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WelcomeBonusIdentifier extends BaseAuditableEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "phone_hash")
    private String phoneHash;

    @Column(name = "email_hash")
    private String emailHash;

    /** Set true when the user closes the entrance-bonus popup (Close / X / Details). */
    @Column(name = "welcome_bonus_popup_shown", nullable = false, columnDefinition = "boolean default false")
    private boolean welcomeBonusPopupShown = false;
}
