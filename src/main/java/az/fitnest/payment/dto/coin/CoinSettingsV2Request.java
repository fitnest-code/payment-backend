package az.fitnest.payment.dto.coin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
public class CoinSettingsV2Request {

    @NotNull
    private String formulaVersion;

    @NotNull
    private Boolean active;

    @NotNull
    @Positive
    private BigDecimal welcomeBonusAmount;

    @NotNull
    @Positive
    private BigDecimal baseEarnRate;

    @NotNull
    @Positive
    private BigDecimal maxGivebackRate;

    @NotNull
    @Positive
    private BigDecimal earnCoinFactor;

    @NotNull
    @Positive
    private BigDecimal spendRateCoinToAzn;

    @NotNull
    @Positive
    private BigDecimal maxDiscountPercentage;

    @NotNull
    @Positive
    private Integer expiryMonths;

    @NotNull
    private Map<String, BigDecimal> tierMultipliers;

    @NotNull
    private Map<Integer, BigDecimal> periodMultipliers;
}
