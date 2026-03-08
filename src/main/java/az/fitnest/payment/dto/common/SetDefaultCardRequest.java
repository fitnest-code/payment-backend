package az.fitnest.payment.dto.common;

import jakarta.validation.constraints.NotNull;

public record SetDefaultCardRequest(
    @NotNull(message = "Card ID is required")
    Long cardId
) {}
