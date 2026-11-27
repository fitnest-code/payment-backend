package az.fitnest.payment.dto.kapital;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KapitalStatusResponse {

    private String providerOrderId;

    private String transactionId;

    /** FitNest status: PENDING, SUCCESS, FAILED, REFUNDED, MANUAL_REVIEW. */
    private String status;

    /** Raw bank status from GET /order (null when answered from local terminal state). */
    private String bankStatus;

    private String pmoResultCode;

    private Double amount;

    private String currency;

    private Integer installmentMonths;
}
