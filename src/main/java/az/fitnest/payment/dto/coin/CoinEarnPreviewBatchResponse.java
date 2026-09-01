package az.fitnest.payment.dto.coin;

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
public class CoinEarnPreviewBatchResponse {
    private String formulaVersion;
    private List<PackageEarnPreview> previews;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PackageEarnPreview {
        private Long packageId;
        private Long optionId;
        private String tier;
        private Integer durationMonths;
        private BigDecimal priceAzn;
        private BigDecimal appliedGivebackRate;
        private Integer awardedCoins;
    }
}
