package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record EpointPaymentRequest(
    @JsonProperty("public_key")
    String publicKey,
    String language,
    @JsonProperty("order_id")
    String orderId,
    Double amount,
    String currency,
    String description,
    @JsonProperty("result_url")
    String resultUrl,
    @JsonProperty("success_redirect_url")
    String successRedirectUrl,
    @JsonProperty("error_redirect_url")
    String errorRedirectUrl,
    @JsonProperty("is_installment")
    Integer isInstallment,
    Integer refund,
    @JsonProperty("other_attr")
    java.util.List<Object> otherAttr
) {}
