package az.fitnest.payment.model.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "coin_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CoinSettings extends BaseAuditableEntity {

    @Column(name = "welcome_bonus_amount", nullable = false)
    private BigDecimal welcomeBonusAmount;

    @Column(name = "earn_rate_azn_to_coin", nullable = false)
    private BigDecimal earnRateAznToCoin;

    @Column(name = "spend_rate_coin_to_azn", nullable = false)
    private BigDecimal spendRateCoinToAzn;

    @Column(name = "max_discount_percentage", nullable = false)
    private BigDecimal maxDiscountPercentage;

    @Column(name = "expiry_months", nullable = false)
    private Integer expiryMonths;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "formula_version", length = 50)
    private String formulaVersion;

    @Column(name = "base_earn_rate")
    private BigDecimal baseEarnRate;

    @Column(name = "max_giveback_rate")
    private BigDecimal maxGivebackRate;

    @Column(name = "earn_coin_factor")
    private BigDecimal earnCoinFactor;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "coin_tier_multipliers", joinColumns = @JoinColumn(name = "settings_id"))
    @MapKeyColumn(name = "tier_name")
    @Column(name = "multiplier")
    private Map<String, BigDecimal> tierMultipliers = new HashMap<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "coin_period_multipliers", joinColumns = @JoinColumn(name = "settings_id"))
    @MapKeyColumn(name = "duration_months")
    @Column(name = "multiplier")
    private Map<Integer, BigDecimal> periodMultipliers = new HashMap<>();
}
