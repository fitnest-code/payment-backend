package az.fitnest.payment.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "coin_wallets", indexes = {
        @Index(name = "idx_coin_wallets_user_id", columnList = "user_id", unique = true),
        @Index(name = "idx_coin_wallets_expiry", columnList = "expiry_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CoinWallet extends BaseAuditableEntity {

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "balance", nullable = false)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "first_coin_earned_at")
    private LocalDateTime firstCoinEarnedAt;

    @Column(name = "expiry_date")
    private LocalDateTime expiryDate;

    public CoinWallet(Long userId, BigDecimal balance) {
        this.userId = userId;
        this.balance = balance;
    }
}
