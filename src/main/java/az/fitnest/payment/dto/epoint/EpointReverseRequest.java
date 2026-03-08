package az.fitnest.payment.dto.epoint;

import lombok.Builder;

@Builder
public record EpointReverseRequest(
    String publicKey,
    String language,
    String transaction,
    Double amount,
    String currency
) {}
