package az.fitnest.payment.dto.common;

public record ApplePaySubmitResponse(
    String status,
    String redirectUrl
) {}
