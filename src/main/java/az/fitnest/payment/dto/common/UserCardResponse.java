package az.fitnest.payment.dto.common;

import java.time.Instant;

public record UserCardResponse(
    Long id,
    String cardId,
    String cardMask,
    String cardName,
    String brand,
    boolean isDefault,
    Instant createdAt,
    Instant updatedAt
) {}
