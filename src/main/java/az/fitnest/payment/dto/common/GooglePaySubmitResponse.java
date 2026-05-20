package az.fitnest.payment.dto.common;

public record GooglePaySubmitResponse(
    String status,
    String redirectUrl
) {}
