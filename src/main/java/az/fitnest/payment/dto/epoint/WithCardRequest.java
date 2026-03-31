package az.fitnest.payment.dto.epoint;

import io.swagger.v3.oas.annotations.media.Schema;

public record WithCardRequest(
    @Schema(description = "Card ID", example = "1234567890")
    String cardId,
    @Schema(description = "Paket ID-si", example = "123")
    Long packageId,
    @Schema(description = "Seçim ID-si", example = "456")
    Long optionId
) {}

