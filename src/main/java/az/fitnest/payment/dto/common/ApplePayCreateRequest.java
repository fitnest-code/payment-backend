package az.fitnest.payment.dto.common;

import jakarta.validation.constraints.NotNull;

public record ApplePayCreateRequest(
    @NotNull(message = "Package ID is required")
    Long packageId,
    @NotNull(message = "Option ID is required")
    Long optionId
) {}
