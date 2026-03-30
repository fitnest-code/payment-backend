package az.fitnest.payment.dto;

import lombok.Builder;

@Builder
public record CardRegistrationResponse(
    String status,
    String code,
    String message,
    String cardId,
    String cardMask,
    String cardName,
    String bankTransaction,
    String bankResponse,
    String operationCode,
    String rrn
) {}
