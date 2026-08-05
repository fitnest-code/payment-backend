package az.fitnest.payment.dto.coin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinSettingsRequest {

    @NotNull
    @Positive
    private BigDecimal welcomeBonusAmount;

    @NotNull
    @Positive
    private BigDecimal earnRateAznToCoin;

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
    private Boolean active;
}
