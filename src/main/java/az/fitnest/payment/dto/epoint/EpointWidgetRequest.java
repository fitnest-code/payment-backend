package az.fitnest.payment.dto.epoint;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
public record EpointWidgetRequest(
    @Schema(hidden = true)
    String publicKey,
    Double amount,
    String currency,
    String orderId,
    String description
) {
    public EpointWidgetRequest setPublicKey(String publicKey) {
        return new EpointWidgetRequest(publicKey, amount, currency, orderId, description);
    }

    public String getPublicKey() {
        return publicKey;
    }
}
