package az.fitnest.payment.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
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
