package az.fitnest.payment.dto.epoint;

public record EpointRefundRequest(
    String publicKey,
    String transactionId,
    Double amount,
    String currency,
    String description
) {}
