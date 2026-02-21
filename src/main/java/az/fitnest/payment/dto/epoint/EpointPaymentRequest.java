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
public class EpointPaymentRequest extends EpointRequestPayload {
    private String language; // az, en, ru
    @JsonProperty("order_id")
    private String orderId;
    private Double amount;
    private String currency; // AZN
    private String description;
    @JsonProperty("success_redirect_url")
    private String successRedirectUrl;
    @JsonProperty("error_redirect_url")
    private String errorRedirectUrl;
}
