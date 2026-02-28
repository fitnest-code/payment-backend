package az.fitnest.payment.dto.epoint;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class EpointInvoiceActionRequest extends EpointRequestPayload {
    private Long id;
    private String phone;
    private String email;
    private String type; // incoming, outgoing, static
    private String order; // ascending, descending
}
