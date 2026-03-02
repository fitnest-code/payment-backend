package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record EpointInvoiceActionRequest(
    @JsonProperty("public_key")
    String publicKey,
    Long id,
    String phone,
    String email,
    String type,
    String order
) {}
