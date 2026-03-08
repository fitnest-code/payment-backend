package az.fitnest.payment.dto.epoint;

public record EpointInvoiceActionRequest(
    String publicKey,
    Long id,
    String phone,
    String email,
    String type,
    String order
) {}
