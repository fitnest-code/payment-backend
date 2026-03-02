package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record EpointSplitExecutePayRequest(
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
    @JsonProperty("split_user")
    String splitUser,
    @JsonProperty("split_amount")
    Double splitAmount,
    @JsonProperty("card_id")
    String cardId
) {}
