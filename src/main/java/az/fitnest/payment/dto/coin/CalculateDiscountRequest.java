package az.fitnest.payment.dto.coin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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

    @NotNull(message = "Orijinal məbləğ icbari hissədir")
    @Positive(message = "Orijinal məbləğ müsbət olmalıdır")
    private BigDecimal originalPrice;

    @NotNull(message = "Xərclənəcək Coin miqdarı icbari hissədir")
    @PositiveOrZero(message = "Coin miqdarı mənfi ola bilməz")
    private BigDecimal coinsToUse;
}
