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
public class CheckoutDiscountItem {
    private String type; // "COIN", "PROMOTION" və s.
    private BigDecimal amount; // AZN endirim məbləği
    private BigDecimal coinsUsed; // COIN tipi olduqda istifadə olunan coin sayı
    private String description;
}
