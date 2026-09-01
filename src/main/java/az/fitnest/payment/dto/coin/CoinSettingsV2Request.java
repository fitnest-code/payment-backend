package az.fitnest.payment.dto.coin;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
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
public class CoinSettingsV2Request {

    private String formulaVersion;

    @NotNull
    private Boolean active;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal welcomeBonusAmount;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal baseEarnRate;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal maxGivebackRate;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal earnCoinFactor;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal spendRateCoinToAzn;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal maxDiscountPercentage;

    @NotNull
    @Min(1)
    private Integer expiryMonths;

    @NotNull
    private Map<String, BigDecimal> tierMultipliers;

    @NotNull
    private Map<Integer, BigDecimal> periodMultipliers;
}
