package az.fitnest.payment.dto.coin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinEarnPreviewResponse {
    private String formulaVersion;
    private String tier;
    private Integer durationMonths;
    private BigDecimal finalPackagePrice;
    private BigDecimal eligibleCashAmount;
    private BigDecimal baseEarnRate;
    private BigDecimal tierMultiplier;
    private BigDecimal periodMultiplier;
    private BigDecimal rawGivebackRate;
    private BigDecimal appliedGivebackRate;
    private BigDecimal earnCoinFactor;
    private BigDecimal rawCoins;
    private Integer awardedCoins;
}
