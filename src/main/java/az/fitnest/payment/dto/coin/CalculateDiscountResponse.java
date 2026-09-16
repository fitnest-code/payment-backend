package az.fitnest.payment.dto.coin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalculateDiscountResponse {
    private CheckoutPlanInfo plan;
    private BigDecimal originalPrice;
    private List<CheckoutDiscountItem> discounts;
    private BigDecimal totalDiscountAmount;
    private BigDecimal finalPaymentAmount;
    private CheckoutCoinInfo coin;
    private Boolean isFullCoinPaymentAvailable;
}
