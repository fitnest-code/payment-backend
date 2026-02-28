package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class EpointWalletPaymentRequest extends EpointRequestPayload {
    @JsonProperty("wallet_id")
    private String walletId;
    private Double amount;
    private String currency; // AZN
    @JsonProperty("order_id")
    private String orderId;
    private String description;
    private String language; // az, en, ru
}
