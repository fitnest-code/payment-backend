package az.fitnest.payment.dto.common;

import jakarta.validation.constraints.NotBlank;

public record ApplePaySubmitRequest(
    @NotBlank(message = "Payment ID is required")
    String paymentId,
    @NotBlank(message = "Apple Pay token is required")
    String token,
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
