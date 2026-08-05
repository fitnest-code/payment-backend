package az.fitnest.payment.dto.coin;

import az.fitnest.payment.model.enums.CoinTransactionType;
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
public class ManualCoinAdjustRequest {

    @NotNull(message = "İstifadəçi ID-si icbari hissədir")
    private Long userId;

    @NotNull(message = "Coin məbləği icbari hissədir")
    @Positive(message = "Məbləğ müsbət olmalıdır")
    private BigDecimal amount;

    @NotNull(message = "Əməliyyat növü icbari hissədir")
    private CoinTransactionType type; // ADJUSTMENT or BONUS

    private String description;
}
