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
public class CheckoutCoinInfo {
    private BigDecimal availableBalance;
    private BigDecimal availableAzn;
    private BigDecimal appliedCoins;
    private BigDecimal discountAzn;
}
