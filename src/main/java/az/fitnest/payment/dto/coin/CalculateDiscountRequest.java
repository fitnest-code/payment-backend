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
public class CalculateDiscountRequest {

    private Long subscriptionPlanId;

    private Long optionId;

    private BigDecimal originalPrice; // Təhlükəsizlik mülahizələrinə görə backend qiyməti DB/gRPC-dən daxili hesablayır

    private Boolean useCoin;

    private BigDecimal coinsToUse;

    private Long promotionId; // Gələcək PromoCode / Kampaniya inteqrasiyası üçün
}
