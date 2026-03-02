package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record EpointReverseRequest(
    @JsonProperty("public_key")
    String publicKey,
    String language,
    String transaction,
    Double amount,
    String currency
) {}
