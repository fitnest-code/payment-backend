package az.fitnest.payment.dto.epoint;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
public record EpointRefundRequest(
    @Schema(hidden = true)
    String publicKey,
    String transactionId,
    Double amount,
    String currency,
    String description
) {
    public EpointRefundRequest setPublicKey(String publicKey) {
        return new EpointRefundRequest(publicKey, transactionId, amount, currency, description);
    }

    public String getPublicKey() {
        return publicKey;
    }
}
