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
}
