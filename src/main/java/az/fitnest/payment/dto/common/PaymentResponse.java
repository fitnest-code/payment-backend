package az.fitnest.payment.dto.common;

import java.time.Instant;

public record PaymentResponse(
    Long paymentId,
    String provider,
    String status,
    String orderId,
    String transactionId,
    Double amount,
    String currency,
    String cardMask,
    String cardName,
    String message,
    Long userId,
    String description,
    String code,
    String bankResponse,
    String operationCode,
    Instant createdAt,
    Instant updatedAt
) {}
