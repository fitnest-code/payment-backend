package az.fitnest.payment.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record PaymentResponse(
    Long paymentId,
    Double amount,
    String currency,
    @JsonSerialize(using = InstantToCustomStringSerializer.class)
    Instant occurredAt,
    String cardBrand,
    String maskedPan,
    String type,
    String status,
    String failureCode,
    String transactionId,
    String owner,
    String description,
    String rrn
) {}
