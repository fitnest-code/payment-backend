package az.fitnest.payment.dto.abb.bnpl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One selectable credit term. {@code value} is what must be sent to ABB; {@code label}
 * is display-only (ABB has not confirmed that 4 means 8 weeks, so it is configurable).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BnplTermOption {
    private Integer value;
    private String label;
}
