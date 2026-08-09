package az.fitnest.payment.dto.abb.bnpl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ABB → FitNest callback payload.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BnplCallbackPayload {
    private Long orderId;
    private String status;
    private Integer partialReverseCount;
    private String timestamp;
}
