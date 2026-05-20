package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EpointTokenResponse(
    String status,
    String transaction,
    String message,
    String code,
    PaymentInfo payment
) {
    public record PaymentInfo(
        String id
    ) {}

    public String getPaymentId() {
        if (payment != null && payment.id() != null) {
            return payment.id();
        }
        return transaction;
    }
}
