package az.fitnest.payment.dto.bob;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bank of Baku ödəniş qaytarma cavabı DTO-su.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BobRefundResponse {

    private String orderId;
    private String errorCode;
    private String errorMessage;
    private Boolean success;
}
