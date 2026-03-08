package az.fitnest.payment.dto.epoint;

import lombok.Builder;

@Builder
public record EpointWalletPaymentRequest(
    String publicKey,
    String walletId,
    Double amount,
    String currency,
    String orderId,
    String description,
    String language
) {
    public EpointWalletPaymentRequest setPublicKey(String publicKey) {
        return new EpointWalletPaymentRequest(publicKey, walletId, amount, currency, orderId, description, language);
    }
}
