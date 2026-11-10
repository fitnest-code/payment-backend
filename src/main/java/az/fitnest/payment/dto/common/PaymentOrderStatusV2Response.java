package az.fitnest.payment.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Lean poll response for checkout: status for UI state, transactionId for receipt lookup
 * via {@code GET /api/v1/me/payments/history/{transactionId}}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "Ödəniş statusu və qəbz üçün transactionId (orderId ilə)")
public record PaymentOrderStatusV2Response(
        @Schema(description = "Normallaşdırılmış status", example = "SUCCESS",
                allowableValues = {"SUCCESS", "FAILED", "CANCELLED", "PENDING"})
        String status,

        @Schema(description = "Provider transaction id; qəbz endpoint üçün", example = "te022241588",
                nullable = true)
        String transactionId
) {
    public static PaymentOrderStatusV2Response of(String status, String transactionId) {
        return new PaymentOrderStatusV2Response(status, transactionId);
    }
}
