package az.fitnest.payment.dto.common;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;

public record GooglePayCreateRequest(
    @NotNull(message = "Package ID is required")
    Long packageId,
    @NotNull(message = "Option ID is required")
    Long optionId,
    @JsonAlias({"coinPaymentEnabled", "coin_payment_enabled", "is_coin_used"})
    Boolean isCoinUsed
) {}
