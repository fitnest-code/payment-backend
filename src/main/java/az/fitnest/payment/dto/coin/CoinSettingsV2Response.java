package az.fitnest.payment.dto.coin;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinSettingsV2Response {
    private Long id;
    private String formulaVersion;
    private Boolean active;
    private BigDecimal welcomeBonusAmount;
    private BigDecimal baseEarnRate;
    private BigDecimal maxGivebackRate;
    private BigDecimal earnCoinFactor;
    private BigDecimal spendRateCoinToAzn;
    private BigDecimal maxDiscountPercentage;
    private Integer expiryMonths;
    private Map<String, BigDecimal> tierMultipliers;
    private Map<Integer, BigDecimal> periodMultipliers;
}
