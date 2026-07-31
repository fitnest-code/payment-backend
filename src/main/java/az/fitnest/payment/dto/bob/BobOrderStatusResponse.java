package az.fitnest.payment.dto.bob;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SmartVista EPG getOrderStatusExtended.do cavab DTO-su.
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
    private String bindingId;
    private String formattedDate;
    private String cardMask;
    private String cardBrand;

    // SmartVista tərəfindən oxunur, amma mobile response-a göndərilmir
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String authRefNum;
}
