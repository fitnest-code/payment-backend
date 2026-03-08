package az.fitnest.payment.dto.epoint;

import lombok.Builder;

@Builder
public record EpointWidgetRequest(
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
