package az.fitnest.payment.dto.common;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

/** V2 Apple Pay create — adds FitNest Coin discount flag. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplePayCreateRequestV2(
    @NotNull(message = "Package ID is required")
    Long packageId,
    @NotNull(message = "Option ID is required")
    Long optionId,
    @JsonAlias({"coinPaymentEnabled", "coin_payment_enabled", "is_coin_used"})
    Boolean isCoinUsed
) {}
