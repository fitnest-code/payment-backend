package az.fitnest.payment.dto.kapital;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KapitalRefundResponse {

    private String orderId;

    private String providerOrderId;

    private boolean success;

    /** VOID_FULL, VOID_PARTIAL or REFUND. */
    private String operation;

    private String errorCode;

    private String errorMessage;
}
