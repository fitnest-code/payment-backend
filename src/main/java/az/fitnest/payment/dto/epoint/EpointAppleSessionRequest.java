package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record EpointAppleSessionRequest(
    @JsonProperty("public_key")
    String publicKey,
    String origin
) {}
