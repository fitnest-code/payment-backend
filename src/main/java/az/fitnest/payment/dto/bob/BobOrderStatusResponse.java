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
@JsonInclude(JsonInclude.Include.NON_NULL)
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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BindingInfo {
        private String clientId;
        private String bindingId;
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
