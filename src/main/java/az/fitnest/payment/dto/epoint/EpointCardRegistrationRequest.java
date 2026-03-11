package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
public record EpointCardRegistrationRequest(
    @Schema(hidden = true)
    @JsonProperty("public_key")
    String publicKey,
    @JsonProperty("language")
    String language,
    @JsonProperty("refund")
    Integer refund,
    @JsonProperty("description")
    String description,
    @Schema(hidden = true)
    @JsonProperty("result_url")
    String resultUrl,
    @Schema(hidden = true)
    @JsonProperty("success_redirect_url")
    String successRedirectUrl,
    @Schema(hidden = true)
    @JsonProperty("error_redirect_url")
    String errorRedirectUrl
) {}
