package az.fitnest.payment.dto.epoint;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record EpointExecutePayRequest(
    @Schema(hidden = true)
    @JsonProperty("public_key")
    String publicKey,

    String language,

    @JsonProperty("order_id")
    String orderId,

    Double amount,
    String currency,
    String description,

    @Schema(hidden = true)
    @JsonProperty("result_url")
    String resultUrl,

    @Schema(hidden = true)
    @JsonProperty("success_redirect_url")
    String successRedirectUrl,

    @Schema(hidden = true)
    @JsonProperty("error_redirect_url")
    String errorRedirectUrl,

    @JsonProperty("card_id")
    String cardId,

    @JsonProperty("is_installment")
    Integer isInstallment,
    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    @com.fasterxml.jackson.annotation.JsonIgnore
    Boolean autoPaymentEnabled
) {}
