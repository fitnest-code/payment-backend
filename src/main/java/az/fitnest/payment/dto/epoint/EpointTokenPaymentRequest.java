package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record EpointTokenPaymentRequest(
    @JsonProperty("public_key")
    String publicKey,
    String transaction,
    @JsonProperty("payment_token")
    String paymentToken,
    @JsonProperty("billing_contact")
    BillingContact billingContact,
    String currency
) {
    public record BillingContact(
        String email,
        String phone,
        String name,
        String address
    ) {}
}
