package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record EpointCardRegistrationRequest(
    @JsonProperty("public_key")
    String publicKey,

    String language,

    Integer refund,

    String description,

    @JsonProperty("result_url")
    String resultUrl,

    @JsonProperty("success_redirect_url")
    String successRedirectUrl,

    @JsonProperty("error_redirect_url")
    String errorRedirectUrl
) {}
