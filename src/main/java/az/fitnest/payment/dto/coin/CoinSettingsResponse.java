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
public class CoinSettingsResponse {
    private Long id;
    private BigDecimal welcomeBonusAmount;
    private BigDecimal earnRateAznToCoin;
    private BigDecimal spendRateCoinToAzn;
    private BigDecimal maxDiscountPercentage;
    private Integer expiryMonths;
    private Boolean active;
}
