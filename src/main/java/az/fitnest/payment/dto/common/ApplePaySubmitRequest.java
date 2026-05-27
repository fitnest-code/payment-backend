package az.fitnest.payment.dto.common;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record ApplePaySubmitRequest(
    @NotBlank(message = "Payment ID is required")
    String paymentId,
    @NotBlank(message = "Apple Pay token is required")
    String token,
    Map<String, Object> billingContact
) {}
