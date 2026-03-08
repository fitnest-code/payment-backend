package az.fitnest.payment.dto.epoint;

public record EpointWidgetRequest(
    String publicKey,
    Double amount,
    String currency,
    String orderId,
    String description
) {}
