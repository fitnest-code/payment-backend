package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record EpointTokenPaymentRequest(
    @JsonProperty("public_key")
    String publicKey,
    @JsonProperty("id")
    String id,
    @JsonProperty("token")
    String token,
    @JsonProperty("billingContact")
    BillingContact billingContact,
    String currency,
    String language
) {
    public record BillingContact(
        String email,
        String phone,
        String name,
        String address
    ) {}
}
