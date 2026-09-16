package az.fitnest.payment.dto.abb;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

/**
 * V2 ABB init body — adds FitNest Coin discount flag.
 */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record AbbInstallmentInitRequestV2(
        Long packageId,
        Long optionId,
        Integer installmentMonths,
        /** FitNest Coin endirimi aktivdir (ödəniş = paket qiyməti − coin AZN). */
        @JsonAlias({"coinPaymentEnabled", "coin_payment_enabled", "is_coin_used"})
        Boolean isCoinUsed
) {
    public AbbInstallmentInitRequest toV1() {
        return new AbbInstallmentInitRequest(packageId, optionId, installmentMonths);
    }
}
