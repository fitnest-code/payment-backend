package az.fitnest.payment.dto.epoint;

import io.swagger.v3.oas.annotations.media.Schema;

public record PayWithCardRequest(
    @Schema(description = "Ödəniş məbləği", example = "29.99")
    Double amount,
    @Schema(description = "Ödəniş valyutası", example = "AZN")
    String currency,
    @Schema(description = "Kart ID-si", example = "ce007840363")
    String cardId
) {}
