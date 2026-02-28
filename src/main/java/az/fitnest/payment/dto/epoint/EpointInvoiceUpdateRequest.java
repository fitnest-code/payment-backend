package az.fitnest.payment.dto.epoint;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class EpointInvoiceUpdateRequest extends EpointInvoiceCreateRequest {
    private Long id;
}
