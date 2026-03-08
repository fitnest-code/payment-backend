package az.fitnest.payment.dto.epoint;

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
