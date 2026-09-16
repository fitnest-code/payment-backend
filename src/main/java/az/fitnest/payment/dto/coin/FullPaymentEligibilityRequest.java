package az.fitnest.payment.dto.coin;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FullPaymentEligibilityRequest {

    @NotNull(message = "Paket ID-si icbari hissədir")
    private Long packageId;

    @NotNull(message = "Option ID-si icbari hissədir")
    private Long optionId;
}
