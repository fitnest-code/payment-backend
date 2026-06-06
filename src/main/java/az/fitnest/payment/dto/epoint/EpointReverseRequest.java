package az.fitnest.payment.dto.epoint;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
public record EpointReverseRequest(
    @Schema(hidden = true)
    @com.fasterxml.jackson.annotation.JsonProperty("public_key")
    String publicKey,
    String language,
    String transaction,
    Double amount,
    String currency
) {}
