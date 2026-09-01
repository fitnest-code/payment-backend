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
public class CoinEarnPreviewRequest {

    @NotNull
    private BigDecimal eligibleCashAmount;

    private Long packageId;

    private Long optionId;

    private String tierName;

    private Integer durationMonths;
}
