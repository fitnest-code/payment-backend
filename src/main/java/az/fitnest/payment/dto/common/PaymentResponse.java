package az.fitnest.payment.dto.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {
    private Long paymentId;
    private String provider;
    private String status;
    private String orderId;
    private String transactionId;
    private Double amount;
    private String currency;
    private String cardMask;
    private String cardName;
    private String message;
    private Long userId;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;
}

