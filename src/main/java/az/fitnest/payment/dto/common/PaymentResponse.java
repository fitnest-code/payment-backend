package az.fitnest.payment.dto.common;

import java.time.Instant;

public record PaymentResponse(
    Long paymentId,
    Double amount,
    String currency,
    Instant occurredAt,
    String cardBrand,
    String maskedPan,
    String type,
    String status,
    String failureCode
) {}
