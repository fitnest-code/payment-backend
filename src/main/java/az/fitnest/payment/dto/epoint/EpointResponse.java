package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
public class EpointResponse {
    private String status; // success, error
    private String transaction;
    @JsonProperty("order_id")
    private String orderId;
    @JsonProperty("redirect_url")
    private String redirectUrl;
    @JsonProperty("bank_transaction")
    private String bankTransaction;
    @JsonProperty("bank_response")
    private String bankResponse;
    @JsonProperty("operation_code")
    private String operationCode;
    private String rrn;
    @JsonProperty("card_name")
    private String cardName;
    @JsonProperty("card_mask")
    private String cardMask;
    private Double amount;
    @JsonProperty("card_id")
    private String cardId;
    @JsonProperty("widget_url")
    private String widgetUrl;
    private String message;
    private String code;
    @JsonProperty("other_attr")
    private Map<String, Object> otherAttr;
}
