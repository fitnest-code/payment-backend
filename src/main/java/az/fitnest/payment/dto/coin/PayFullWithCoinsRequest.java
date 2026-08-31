package az.fitnest.payment.dto.coin;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayFullWithCoinsRequest {

    @NotNull(message = "Abunəlik plan ID-si icbari hissədir")
    private Long subscriptionPlanId;

    private Long optionId;

    private BigDecimal originalPrice; // Təhlükəsizlik mülahizələrinə görə backend qiyməti DB/gRPC-dən təyin edir
}
