package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record EpointExecutePayRequest(
    @JsonProperty("public_key")
    String publicKey,
    String language,
    @JsonProperty("order_id")
    String orderId,
    Double amount,
    String currency,
    String description,
    @JsonProperty("success_redirect_url")
    String successRedirectUrl,
    @JsonProperty("error_redirect_url")
    String errorRedirectUrl,
    @JsonProperty("card_id")
    String cardId,
    @JsonProperty("is_installment")
    Integer isInstallment
) {}
