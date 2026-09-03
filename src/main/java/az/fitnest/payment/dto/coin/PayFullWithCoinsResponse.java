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
public class PayFullWithCoinsResponse {
    private Boolean success;
    private String orderId;
    private Long subscriptionPlanId;
    private Long optionId;
    private Integer durationMonths;
    private BigDecimal coinsDeducted;
    private BigDecimal remainingBalance;
    private String message;
}
