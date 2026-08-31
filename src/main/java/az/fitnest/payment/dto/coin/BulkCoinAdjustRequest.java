package az.fitnest.payment.dto.coin;

import az.fitnest.payment.model.enums.CoinTransactionType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
public class BulkCoinAdjustRequest {

    @NotEmpty(message = "İstifadəçi ID siyahısı boş ola bilməz")
    private List<Long> userIds;

    @NotNull(message = "Coin məbləği icbari hissədir")
    private BigDecimal amount;

    private CoinTransactionType type; // BONUS or ADJUSTMENT

    private String description;

    private String notificationTitle;

    private String notificationBody;

    private Boolean sendNotification;
}
