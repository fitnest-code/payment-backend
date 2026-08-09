package az.fitnest.payment.dto.abb.bnpl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ABB Submit Order success response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BnplAbbSubmitResponse {
    private Long orderId;
    private String message;
    private String status;
}
