package az.fitnest.payment.dto.bob;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bank of Baku vasitəsilə ödənişi geri qaytarma sorğusu (Refund).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BobRefundRequest {

    @NotBlank(message = "Order ID məcburidir")
    private String orderId;

    @NotNull(message = "Məbləğ məcburidir")
    private Double amount;
}
