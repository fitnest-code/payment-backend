package az.fitnest.payment.dto.bob;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SmartVista EPG getOrderStatusExtended.do cavab DTO-su.
 * Mobile API omits bank-only binding fields; those stay write-only for card-save internals.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.ALWAYS)
public class BobOrderStatusResponse {

    private String errorCode;
    private String errorMessage;
    private Integer orderStatus;
    private String orderNumber;
    private Long amount;
    private Integer currency;
    private String rrn;
    private String approvalCode;
    private String pan;
    private String cardholderName;
    private String formattedDate;
    private String cardMask;
    private String cardBrand;
    private String bank;
    private String type;

    /** Bank may return this; never exposed on mobile status API. */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String bindingId;

    // SmartVista tərəfindən oxunur, amma Mobile response-a serializasiya olunmur (WRITE_ONLY)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private BindingInfo bindingInfo;

    /**
     * SmartVista nests pan / cardholder / approval / paymentSystem under cardAuthInfo.
     * Flattened into root fields before returning to clients.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private CardAuthInfo cardAuthInfo;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BindingInfo {
        private String clientId;
        private String bindingId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CardAuthInfo {
        private String pan;
        private String expiration;
        private String cardholderName;
        private String paymentSystem;
        private String approvalCode;
        private String authorizationResponseId;
    }

    /** Internal helper for card save — not part of API response. */
    @JsonIgnore
    public String getResolvedBindingId() {
        if (bindingId != null && !bindingId.isBlank()) {
            return bindingId;
        }
        if (bindingInfo != null && bindingInfo.getBindingId() != null && !bindingInfo.getBindingId().isBlank()) {
            return bindingInfo.getBindingId();
        }
        return null;
    }

    /** Payment network from cardAuthInfo (VISA, MASTERCARD, …). */
    @JsonIgnore
    public String getResolvedPaymentSystem() {
        if (cardAuthInfo == null) {
            return null;
        }
        String ps = cardAuthInfo.getPaymentSystem();
        return ps != null && !ps.isBlank() ? ps.trim() : null;
    }

    /**
     * Copies nested cardAuthInfo / binding fields onto root DTO fields used by the API.
     */
    public void flattenBankPayload() {
        if (cardAuthInfo != null) {
            if (isBlank(pan) && !isBlank(cardAuthInfo.getPan())) {
                pan = cardAuthInfo.getPan();
            }
            if (isBlank(cardholderName) && !isBlank(cardAuthInfo.getCardholderName())) {
                cardholderName = cardAuthInfo.getCardholderName();
            }
            if (isBlank(approvalCode)) {
                if (!isBlank(cardAuthInfo.getApprovalCode())) {
                    approvalCode = cardAuthInfo.getApprovalCode();
                } else if (!isBlank(cardAuthInfo.getAuthorizationResponseId())) {
                    approvalCode = cardAuthInfo.getAuthorizationResponseId();
                }
            }
        }
        if (isBlank(rrn) && !isBlank(authRefNum)) {
            rrn = authRefNum;
        }
        if (isBlank(bindingId) && bindingInfo != null && !isBlank(bindingInfo.getBindingId())) {
            bindingId = bindingInfo.getBindingId();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String actionCode;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String actionCodeDescription;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String receiptNumber;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String ip;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String date;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String authDateTime;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String terminalId;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String authRefNum;
}
