package az.fitnest.payment.dto.common;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record GooglePaySubmitRequest(
    @NotBlank(message = "Payment ID is required")
    String paymentId,
    @NotBlank(message = "Google Pay token is required")
    String token,
    @NotNull(message = "Billing address is required")
    BillingAddress billingAddress
) {
    public record BillingAddress(
        String name,
        String postalCode,
        String countryCode,
        String phoneNumber,
        String locality,
        String address1,
        String address2
    ) {}
}
