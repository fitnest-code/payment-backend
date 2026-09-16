package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * V1 checkout body — pre-coin contract. Extra fields (e.g. isCoinUsed) are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CurrencyRequest(
    @Schema(description = "Paket ID-si", example = "123")
    Long packageId,
    @Schema(description = "Seçim ID-si", example = "456")
    Long optionId,
    @Schema(description = "Avtomatik ödəniş aktivdir", example = "true")
    Boolean autoPaymentEnabled
) {}
