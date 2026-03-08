package az.fitnest.payment.dto.epoint;

import lombok.Builder;

@Builder
public record EpointExecutePayRequest(
    String publicKey,
    String language,
    String orderId,
    Double amount,
    String currency,
    String description,
    String resultUrl,
    String successRedirectUrl,
    String errorRedirectUrl,
    String cardId,
    Integer isInstallment
) {}
