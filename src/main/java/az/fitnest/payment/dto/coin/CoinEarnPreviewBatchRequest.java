package az.fitnest.payment.dto.coin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
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
public class CoinEarnPreviewBatchRequest {

    @NotEmpty
    @Valid
    private List<PackageEarnItem> packages;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PackageEarnItem {
        private Long packageId;
        private Long optionId;
        private String tierName;
        private Integer durationMonths;
        private BigDecimal priceAzn;
    }
}
