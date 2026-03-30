package az.fitnest.payment.dto.epoint;

import io.swagger.v3.oas.annotations.media.Schema;

public record CurrencyRequest(
    @Schema(description = "Paket ID-si", example = "123")
    Long packageId,
    @Schema(description = "Seçim ID-si", example = "456")
    Long optionId
) {}
