package az.fitnest.payment.dto.epoint;

import lombok.Builder;

@Builder
public record EpointInvoiceActionRequest(
    @com.fasterxml.jackson.annotation.JsonProperty("public_key")
    String publicKey,
    Long id,
    String phone,
    String email,
    String type,
    String order
) {}
