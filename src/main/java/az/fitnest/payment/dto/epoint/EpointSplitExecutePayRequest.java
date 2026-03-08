package az.fitnest.payment.dto.epoint;

public record EpointSplitExecutePayRequest(
    String publicKey,
    String language,
    String orderId,
    Double amount,
    String currency,
    String description,
    String resultUrl,
    String successRedirectUrl,
    String errorRedirectUrl,
    String splitUser,
    Double splitAmount,
    String cardId
) {}
