package az.fitnest.payment.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "coin_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CoinSettings extends BaseAuditableEntity {

    @Column(name = "welcome_bonus_amount", nullable = false)
    private BigDecimal welcomeBonusAmount = new BigDecimal("50.00");

    @Column(name = "earn_rate_azn_to_coin", nullable = false)
    private BigDecimal earnRateAznToCoin = new BigDecimal("1.00");

    @Column(name = "spend_rate_coin_to_azn", nullable = false)
    private BigDecimal spendRateCoinToAzn = new BigDecimal("20.00");

    @Column(name = "max_discount_percentage", nullable = false)
    private BigDecimal maxDiscountPercentage = new BigDecimal("20.00");

    @Column(name = "expiry_months", nullable = false)
    private Integer expiryMonths = 12;

    @Column(name = "active", nullable = false)
    private Boolean active = true;
}
