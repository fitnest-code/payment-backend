package az.fitnest.payment.dto.abb.bnpl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Mobile-facing BNPL order status.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BnplStatusResponse {
    private String reference;
    private String abbOrderId;
    private String abbStatus;
    private String paymentStatus;
    private String message;
    private boolean terminal;
    private boolean success;
    private Double amount;
    private String currency;
    private Integer term;
    private Integer partialReverseCount;

    /** Sum of all partial reversals already executed (AZN). */
    private Double reversedAmount;

    /** Remaining amount that can still be reversed (AZN). */
    private Double reversableAmount;

    /** True only when the order is COMPLETED and has no partial reversal yet. */
    private boolean fullReverseAllowed;

    /** True only when the order is COMPLETED and not yet fully reversed. */
    private boolean partialReverseAllowed;

    /** True when this order may still change (INIT / IN_PROGRESS / unknown submit). */
    private boolean retryable;

    /** True when the local submit result was never confirmed by ABB. */
    private boolean submitUnknown;
}
