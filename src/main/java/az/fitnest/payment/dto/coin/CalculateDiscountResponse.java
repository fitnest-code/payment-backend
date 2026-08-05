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
public class CalculateDiscountResponse {
    private BigDecimal originalPrice;
    private BigDecimal coinsToUse;
    private BigDecimal appliedDiscountAzn;
    private BigDecimal finalPaymentAmount;
    private BigDecimal maxDiscountLimitAzn;
    private Boolean isMaxDiscountReached;
}
