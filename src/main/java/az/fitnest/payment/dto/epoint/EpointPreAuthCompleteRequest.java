package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record EpointPreAuthCompleteRequest(
    @JsonProperty("public_key")
    String publicKey,
    Double amount,
    String transaction
) {
    public EpointPreAuthCompleteRequest setPublicKey(String publicKey) {
        return new EpointPreAuthCompleteRequest(publicKey, amount, transaction);
    }
}
