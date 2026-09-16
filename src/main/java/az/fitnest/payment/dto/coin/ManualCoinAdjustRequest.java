package az.fitnest.payment.dto.coin;

import az.fitnest.payment.model.enums.CoinTransactionType;
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
public class ManualCoinAdjustRequest {

    @NotNull(message = "İstifadəçi ID-si icbari hissədir")
    private Long userId;

    @NotNull(message = "Coin məbləği icbari hissədir")
    private BigDecimal amount;

    private CoinTransactionType type; // ADJUSTMENT or BONUS

    private String description;

    private String notificationTitle;

    private String notificationBody;

    private Boolean sendNotification;
}
