package az.fitnest.payment.dto.coin;

import jakarta.validation.constraints.DecimalMin;
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
public class SetCoinBalanceRequest {

    @NotNull(message = "Coin balansı icbari hissədir")
    @DecimalMin(value = "0.00", message = "Coin balansı mənfi ola bilməz")
    private BigDecimal balance;

    private String description;

    private String notificationTitle;

    private String notificationBody;

    private Boolean sendNotification;
}
