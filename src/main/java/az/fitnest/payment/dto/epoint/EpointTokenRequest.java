package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record EpointTokenRequest(
    @JsonProperty("public_key")
    String publicKey,
    Double amount,
    String currency,
    @JsonProperty("order_id")
    String orderId,
    String description
) {}
