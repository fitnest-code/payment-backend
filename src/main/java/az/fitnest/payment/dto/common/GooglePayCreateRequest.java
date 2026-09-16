package az.fitnest.payment.dto.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

/** V1 Google Pay create — pre-coin contract. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GooglePayCreateRequest(
    @NotNull(message = "Package ID is required")
    Long packageId,
    @NotNull(message = "Option ID is required")
    Long optionId
) {}
