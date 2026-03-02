package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record EpointWalletPaymentRequest(
    @JsonProperty("public_key")
    String publicKey,
    @JsonProperty("wallet_id")
    String walletId,
    Double amount,
    String currency,
    @JsonProperty("order_id")
    String orderId,
    String description,
    String language
) {}
