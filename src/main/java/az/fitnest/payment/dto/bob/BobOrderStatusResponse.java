package az.fitnest.payment.dto.bob;

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
public class BobOrderStatusResponse {

    private String errorCode;
    private String errorMessage;
    private Integer orderStatus;
    private String orderNumber;
    private Long amount;
    private Integer currency;
    private String actionCode;
    private String actionCodeDescription;
    private String rrn;
    private String approvalCode;
    private String pan;
    private String cardholderName;
    private String bindingId;
    private String ip;
}
