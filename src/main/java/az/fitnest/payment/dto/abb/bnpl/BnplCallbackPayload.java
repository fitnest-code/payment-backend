package az.fitnest.payment.dto.abb.bnpl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ABB → FitNest callback payload.
 *
 * <p>Only {@code orderId} and {@code status} are guaranteed; ABB said reference is not
 * unique and is not searchable, so it is stored as a hint, never as a primary key.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BnplCallbackPayload {
    private Long orderId;
    private String status;
    private Integer partialReverseCount;
    private Double partialReverseAmount;
    private String reference;
    private String message;
    private String errorCode;
    private String timestamp;
}
