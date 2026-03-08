package az.fitnest.payment.dto.epoint;

import lombok.Builder;

@Builder
public record EpointRequestPayload(
    String publicKey
) {}
