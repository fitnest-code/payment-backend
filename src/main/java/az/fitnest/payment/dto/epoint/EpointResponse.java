package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.Builder;

@Builder
public record EpointResponse(
    String status,
    String transaction,
    @JsonProperty("order_id")
    String orderId,
    @JsonProperty("redirect_url")
    String redirectUrl,
    @JsonProperty("bank_transaction")
    String bankTransaction,
    @JsonProperty("bank_response")
    String bankResponse,
    @JsonProperty("operation_code")
    String operationCode,
    String rrn,
    @JsonProperty("card_name")
    String cardName,
    @JsonProperty("card_mask")
    String cardMask,
    Double amount,
    @JsonProperty("split_amount")
    Double splitAmount,
    @JsonProperty("card_id")
    String cardId,
    @JsonProperty("widget_url")
    String widgetUrl,
    String message,
    String code,
    @JsonProperty("other_attr")
    String otherAttr,
    @JsonProperty("approval_code")
    String approvalCode,
    @JsonProperty("card_number")
    String cardNumber,
    @JsonProperty("recc_pmnt_id")
    String reccPmntId
) {
    public EpointResponse withApprovalCode(String approvalCode) {
        return new EpointResponse(
            status, transaction, orderId, redirectUrl, bankTransaction,
            bankResponse, operationCode, rrn, cardName, cardMask,
            amount, splitAmount, cardId, widgetUrl, message, code,
            otherAttr, approvalCode, cardNumber, reccPmntId
        );
    }

    public EpointResponse withOrderId(String orderId) {
        return new EpointResponse(
            status, transaction, orderId, redirectUrl, bankTransaction,
            bankResponse, operationCode, rrn, cardName, cardMask,
            amount, splitAmount, cardId, widgetUrl, message, code,
            otherAttr, approvalCode, cardNumber, reccPmntId
        );
    }
}
