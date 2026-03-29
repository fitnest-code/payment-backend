package az.fitnest.payment.dto.common;

import java.time.Instant;

public record UserCardResponse(
    Long id,
    String cardId,
    String cardMask,
    String cardName,
    String brand,
    String bankTransaction,
    String bankResponse,
    String operationCode,
    String rrn,
    String approvalCode,
    String cardNumber,
    String reccPmntId,
    String reccPmntExpiry,
    Instant createdAt,
    Instant updatedAt
) {}
