package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * V2 checkout body — same as v1 plus optional FitNest Coin discount flag.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CurrencyRequestV2(
    @Schema(description = "Paket ID-si", example = "123")
    Long packageId,
    @Schema(description = "Seçim ID-si", example = "456")
    Long optionId,
    @Schema(description = "Avtomatik ödəniş aktivdir", example = "true")
    Boolean autoPaymentEnabled,
    @Schema(description = "FitNest Coin endirimi aktivdir (ödəniş = paket qiyməti − coin AZN ekvivalenti)", example = "false")
    @JsonAlias({"coinPaymentEnabled", "coin_payment_enabled", "is_coin_used"})
    Boolean isCoinUsed
) {
    public CurrencyRequest toV1() {
        return new CurrencyRequest(packageId, optionId, autoPaymentEnabled);
    }
}
