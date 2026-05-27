package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import java.util.Map;

@Builder
public record EpointAppleTokenPaymentRequest(
    @JsonProperty("public_key")
    String publicKey,
    @JsonProperty("id")
    String id,
    @JsonProperty("token")
    String token,
    @JsonProperty("billingContact")
    Map<String, Object> billingContact,
    String currency,
    String language
) {}
