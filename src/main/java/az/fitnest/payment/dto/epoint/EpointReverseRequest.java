package az.fitnest.payment.dto.epoint;

public record EpointReverseRequest(
    String publicKey,
    String language,
    String transaction,
    Double amount,
    String currency
) {}
