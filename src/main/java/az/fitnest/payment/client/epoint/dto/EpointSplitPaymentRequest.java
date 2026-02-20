package az.fitnest.payment.client.epoint.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class EpointSplitPaymentRequest extends EpointPaymentRequest {
    @JsonProperty("split_user")
    private String splitUser;
    @JsonProperty("split_amount")
    private Double splitAmount;
}
