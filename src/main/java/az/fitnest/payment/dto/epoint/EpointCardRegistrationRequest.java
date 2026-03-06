package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record EpointCardRegistrationRequest(
    @JsonProperty("public_key")
    String publicKey,

    String language,

    // Optional fields
    Integer refund,

    String description,

    @JsonProperty("success_redirect_url")
    String successRedirectUrl,

    @JsonProperty("error_redirect_url")
    String errorRedirectUrl
) {}

