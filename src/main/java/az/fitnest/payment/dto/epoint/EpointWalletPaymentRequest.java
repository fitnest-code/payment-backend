package az.fitnest.payment.dto.epoint;

import lombok.Builder;

@Builder
public record EpointWalletPaymentRequest(
    @com.fasterxml.jackson.annotation.JsonProperty("public_key")
    String publicKey,
    @com.fasterxml.jackson.annotation.JsonProperty("wallet_id")
    String walletId,
    Double amount,
    String currency,
    @com.fasterxml.jackson.annotation.JsonProperty("order_id")
    String orderId,
    String description,
    String language
) {
    public EpointWalletPaymentRequest setPublicKey(String publicKey) {
        return new EpointWalletPaymentRequest(publicKey, walletId, amount, currency, orderId, description, language);
    }
}
