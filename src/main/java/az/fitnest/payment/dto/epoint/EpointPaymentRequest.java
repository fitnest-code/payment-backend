package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
public record EpointPaymentRequest(
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
    @JsonProperty("is_installment")
    Integer isInstallment,
    Integer refund,
    @JsonProperty("other_attr")
    java.util.List<String> otherAttr
) {}
