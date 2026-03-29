package az.fitnest.payment.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "user_cards", indexes = {
        @Index(name = "idx_user_cards_user_id", columnList = "user_id"),
        @Index(name = "idx_user_cards_card_id", columnList = "card_id")
},
       uniqueConstraints = {@UniqueConstraint(columnNames = {"user_id", "card_id"})})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserCard extends BaseAuditableEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "card_id", nullable = false)
    private String cardId;

    @Column(name = "card_mask", nullable = false)
    private String cardMask;

    @Column(name = "card_name")
    private String cardName;

    @Column(name = "brand")
    private String brand;

    @Column(name = "bank_transaction")
    private String bankTransaction;

    @Column(name = "bank_response")
    private String bankResponse;

    @Column(name = "operation_code")
    private String operationCode;

    @Column(name = "rrn")
    private String rrn;
}
