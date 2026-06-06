package az.fitnest.payment.dto.epoint;

import lombok.Builder;

@Builder
public record EpointRequestPayload(
    @com.fasterxml.jackson.annotation.JsonProperty("public_key")
    String publicKey
) {}
