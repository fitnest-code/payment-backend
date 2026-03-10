package az.fitnest.payment.dto.epoint;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
public record EpointCardRegistrationRequest(
    @Schema(hidden = true)
    String publicKey,
    String language,
    Integer refund,
    String description,
    @Schema(hidden = true)
    String resultUrl,
    @Schema(hidden = true)
    String successRedirectUrl,
    @Schema(hidden = true)
    String errorRedirectUrl
) {}
