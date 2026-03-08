package az.fitnest.payment.dto.epoint;

public record EpointStatusRequest(
    String publicKey,
    String transaction
) {}
