package az.fitnest.payment.dto.epoint;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class EpointReverseRequest extends EpointRequestPayload {
    private String language;
    private String transaction;
    private Double amount;
    private String currency;
}
