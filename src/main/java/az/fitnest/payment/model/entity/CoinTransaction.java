package az.fitnest.payment.model.entity;

import az.fitnest.payment.model.enums.CoinTransactionType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "coin_transactions", indexes = {
        @Index(name = "idx_coin_tx_user_id", columnList = "user_id"),
        @Index(name = "idx_coin_tx_wallet_id", columnList = "wallet_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CoinTransaction extends BaseAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private CoinWallet wallet;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private CoinTransactionType type;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false)
    private BigDecimal balanceAfter;

    @Column(name = "order_id")
    private String orderId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "expiry_date")
    private LocalDateTime expiryDate;

    @Column(name = "remaining_amount")
    private BigDecimal remainingAmount = BigDecimal.ZERO;

    @Column(name = "description")
    private String description;
}
