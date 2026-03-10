package az.fitnest.payment.dto.epoint;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
public record EpointExecutePayRequest(
    @Schema(hidden = true)
    String publicKey,
    String language,
    String orderId,
    Double amount,
    String currency,
    String description,
    @Schema(hidden = true)
    String resultUrl,
    @Schema(hidden = true)
    String successRedirectUrl,
    @Schema(hidden = true)
    String errorRedirectUrl,
    String cardId,
    Integer isInstallment
) {}
