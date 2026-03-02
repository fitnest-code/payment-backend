package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record EpointRequestPayload(
    @JsonProperty("public_key")
    String publicKey
) {}
