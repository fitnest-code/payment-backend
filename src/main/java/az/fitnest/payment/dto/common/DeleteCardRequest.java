package az.fitnest.payment.dto.common;

import jakarta.validation.constraints.NotBlank;

public record DeleteCardRequest(@NotBlank String cardId) {}
